package com.oms.ingress;

import com.epam.deltix.gflog.api.Log;
import com.epam.deltix.gflog.api.LogFactory;
import com.oms.sbe.AmendOrderCommandEncoder;
import com.oms.sbe.CancelOrderCommandEncoder;
import com.oms.sbe.CancelReason;
import com.oms.sbe.MessageHeaderEncoder;
import com.oms.sbe.NewOrderCommandEncoder;
import com.oms.sbe.OrderType;
import com.oms.sbe.Side;
import com.oms.sbe.TimeInForce;
import com.sun.net.httpserver.HttpServer;
import io.aeron.Publication;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP → Aeron command gateway.
 *
 * <p>Runs a JDK {@link HttpServer} on port 8080. HTTP threads hand off parsed requests
 * via a lock-free {@link ManyToOneConcurrentArrayQueue} to the single-consumer AgentRunner thread,
 * which encodes them into SBE and offers them to the Command Ingress stream (StreamId 10).
 *
 * <p>Supported endpoints:
 * <ul>
 *   <li>POST   /orders — new order
 *   <li>DELETE /orders — cancel order
 *   <li>PATCH  /orders — amend order (price and/or quantity)
 * </ul>
 *
 * TODO(POC): replace manual JSON parsing with a proper parser; add TLS/auth.
 */
public class OrderIngressAgent implements Agent {

    private static final Log log = LogFactory.getLog(OrderIngressAgent.class);
    private static final int HTTP_PORT = 8080;

    // Pre-allocated — never allocate inside doWork()
    private final UnsafeBuffer          encodingBuffer = new UnsafeBuffer(new byte[512]);
    private final MessageHeaderEncoder  headerEncoder  = new MessageHeaderEncoder();
    private final NewOrderCommandEncoder   cmdEncoder    = new NewOrderCommandEncoder();
    private final CancelOrderCommandEncoder cancelEncoder = new CancelOrderCommandEncoder();
    private final AmendOrderCommandEncoder  amendEncoder  = new AmendOrderCommandEncoder();

    // Many-to-one: HTTP handler threads are producers; AgentRunner thread is the single consumer.
    private final ManyToOneConcurrentArrayQueue<HttpRequest> requestQueue =
            new ManyToOneConcurrentArrayQueue<>(1024);
    private final AtomicLong correlationIdGenerator = new AtomicLong(1L);

    private final Publication commandIngressPub;
    private HttpServer httpServer;

    public OrderIngressAgent(Publication commandIngressPub) {
        this.commandIngressPub = commandIngressPub;
    }

    @Override
    public void onStart() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), /*backlog=*/0);

            // POST /orders — new order
            httpServer.createContext("/orders", exchange -> {
                try {
                    final String method = exchange.getRequestMethod();
                    final String body = new String(
                            exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

                    if ("POST".equalsIgnoreCase(method)) {
                        final NewOrderRequest req = parseNewOrder(body);
                        if (req == null) {
                            sendJson(exchange, 400, "{\"error\":\"invalid or missing fields\"}");
                            return;
                        }
                        requestQueue.offer(req);
                        sendJson(exchange, 202, "{\"correlationId\":" + req.correlationId() + "}");

                    } else if ("DELETE".equalsIgnoreCase(method)) {
                        final CancelOrderRequest req = parseCancelOrder(body);
                        if (req == null) {
                            sendJson(exchange, 400, "{\"error\":\"invalid or missing fields\"}");
                            return;
                        }
                        requestQueue.offer(req);
                        sendJson(exchange, 202, "{\"correlationId\":" + req.correlationId() + "}");

                    } else if ("PATCH".equalsIgnoreCase(method)) {
                        final AmendOrderRequest req = parseAmendOrder(body);
                        if (req == null) {
                            sendJson(exchange, 400, "{\"error\":\"invalid or missing fields\"}");
                            return;
                        }
                        requestQueue.offer(req);
                        sendJson(exchange, 202, "{\"correlationId\":" + req.correlationId() + "}");

                    } else {
                        exchange.sendResponseHeaders(405, -1);
                        exchange.close();
                    }
                } catch (Exception e) {
                    log.error().append("[ingress] HTTP handler error: ").append(e.getMessage()).commit();
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                }
            });

            // TODO(POC): bound the thread pool in production.
            httpServer.setExecutor(Executors.newCachedThreadPool());
            httpServer.start();
            log.info().append("[ingress] HTTP server listening on :").append(HTTP_PORT).commit();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start HTTP server on port " + HTTP_PORT, e);
        }
    }

    @Override
    public int doWork() {
        // Wait until the Sequencer subscription is ready before publishing
        if (!commandIngressPub.isConnected()) {
            return 0;
        }

        final HttpRequest req = requestQueue.poll();
        if (req == null) {
            return 0;
        }

        if (req instanceof NewOrderRequest noc) {
            return encodeAndOfferNewOrder(noc);
        } else if (req instanceof CancelOrderRequest cor) {
            return encodeAndOfferCancelOrder(cor);
        } else if (req instanceof AmendOrderRequest aor) {
            return encodeAndOfferAmendOrder(aor);
        }
        return 0;
    }

    @Override
    public String roleName() { return "order-ingress"; }

    @Override
    public void onClose() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        log.info().append("order-ingress closed").commit();
    }

    // ── Encoders ──────────────────────────────────────────────────────────────

    private int encodeAndOfferNewOrder(NewOrderRequest req) {
        cmdEncoder.wrapAndApplyHeader(encodingBuffer, 0, headerEncoder)
                .sequenceNumber(0)   // Sequencer overwrites at byte offset 8
                .timestamp(System.nanoTime())
                .correlationId(req.correlationId())
                .orderId(req.orderId())
                .accountId(req.accountId())
                .instrument(req.instrument())
                .side(req.side())
                .orderType(req.orderType())
                .timeInForce(req.timeInForce())
                .price(req.price())
                .quantity(req.quantity());

        final int msgLen = MessageHeaderEncoder.ENCODED_LENGTH + NewOrderCommandEncoder.BLOCK_LENGTH;
        final long result = commandIngressPub.offer(encodingBuffer, 0, msgLen);
        if (result > 0) {
            log.info()
                .append("[ingress] published NewOrderCommand orderId=").append(req.orderId())
                .append(" correlationId=").append(req.correlationId())
                .commit();
            return 1;
        } else {
            // TODO(POC): re-queue on back-pressure; drop for now
            log.warn()
                .append("[ingress] failed to publish NewOrderCommand orderId=").append(req.orderId())
                .append(" result=").append(result)
                .commit();
            return 0;
        }
    }

    private int encodeAndOfferCancelOrder(CancelOrderRequest req) {
        cancelEncoder.wrapAndApplyHeader(encodingBuffer, 0, headerEncoder)
                .sequenceNumber(0)
                .timestamp(System.nanoTime())
                .correlationId(req.correlationId())
                .orderId(req.orderId())
                .accountId(req.accountId())
                .cancelReason(req.reason());

        final int msgLen = MessageHeaderEncoder.ENCODED_LENGTH + CancelOrderCommandEncoder.BLOCK_LENGTH;
        final long result = commandIngressPub.offer(encodingBuffer, 0, msgLen);
        if (result > 0) {
            log.info()
                .append("[ingress] published CancelOrderCommand orderId=").append(req.orderId())
                .append(" correlationId=").append(req.correlationId())
                .commit();
            return 1;
        } else {
            log.warn()
                .append("[ingress] failed to publish CancelOrderCommand orderId=").append(req.orderId())
                .append(" result=").append(result)
                .commit();
            return 0;
        }
    }

    private int encodeAndOfferAmendOrder(AmendOrderRequest req) {
        amendEncoder.wrapAndApplyHeader(encodingBuffer, 0, headerEncoder)
                .sequenceNumber(0)
                .timestamp(System.nanoTime())
                .correlationId(req.correlationId())
                .orderId(req.orderId())
                .accountId(req.accountId())
                .newPrice(req.newPrice())
                .newQuantity(req.newQty());

        final int msgLen = MessageHeaderEncoder.ENCODED_LENGTH + AmendOrderCommandEncoder.BLOCK_LENGTH;
        final long result = commandIngressPub.offer(encodingBuffer, 0, msgLen);
        if (result > 0) {
            log.info()
                .append("[ingress] published AmendOrderCommand orderId=").append(req.orderId())
                .append(" correlationId=").append(req.correlationId())
                .commit();
            return 1;
        } else {
            log.warn()
                .append("[ingress] failed to publish AmendOrderCommand orderId=").append(req.orderId())
                .append(" result=").append(result)
                .commit();
            return 0;
        }
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────
    // Regex-based: no external dependency. POC-acceptable.
    // TODO(POC): replace with a proper JSON parser (Jackson, Gson, etc.) in production.

    // Matches numeric values — both integer (1001) and decimal (150.00)
    private static final Pattern NUM_FIELD =
            Pattern.compile("\"(\\w+)\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
    // Matches string values
    private static final Pattern STRING_FIELD =
            Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]+)\"");

    private NewOrderRequest parseNewOrder(String body) {
        try {
            long orderId = 0, accountId = 0;
            double price = 0.0, quantity = 0.0;
            String instrument = null, side = null, orderType = null, timeInForce = null;

            Matcher m = STRING_FIELD.matcher(body);
            while (m.find()) {
                switch (m.group(1)) {
                    case "instrument"  -> instrument  = m.group(2);
                    case "side"        -> side        = m.group(2);
                    case "orderType"   -> orderType   = m.group(2);
                    case "timeInForce" -> timeInForce = m.group(2);
                }
            }

            m = NUM_FIELD.matcher(body);
            while (m.find()) {
                final String val = m.group(2);
                switch (m.group(1)) {
                    case "orderId"   -> orderId   = Long.parseLong(val);
                    case "accountId" -> accountId = Long.parseLong(val);
                    case "price"     -> price     = Double.parseDouble(val);
                    case "quantity"  -> quantity  = Double.parseDouble(val);
                }
            }

            if (instrument == null || side == null || orderType == null || timeInForce == null
                    || orderId == 0) {
                return null;
            }

            // Pad/truncate to the fixed SBE 12-char instrument field
            final String paddedInstrument = String.format("%-12s", instrument).substring(0, 12);
            final long priceRaw    = (long)(price    * 1e8);
            final long quantityRaw = (long)(quantity * 1e8);
            final long correlationId = correlationIdGenerator.getAndIncrement();

            return new NewOrderRequest(
                    correlationId, orderId, accountId, paddedInstrument,
                    Side.valueOf(side), OrderType.valueOf(orderType), TimeInForce.valueOf(timeInForce),
                    priceRaw, quantityRaw);
        } catch (Exception e) {
            log.warn().append("[ingress] failed to parse new order: ").append(e.getMessage()).commit();
            return null;
        }
    }

    private CancelOrderRequest parseCancelOrder(String body) {
        try {
            long orderId = 0, accountId = 0;
            String reason = null;

            Matcher m = STRING_FIELD.matcher(body);
            while (m.find()) {
                if ("reason".equals(m.group(1))) reason = m.group(2);
            }

            m = NUM_FIELD.matcher(body);
            while (m.find()) {
                final String val = m.group(2);
                switch (m.group(1)) {
                    case "orderId"   -> orderId   = Long.parseLong(val);
                    case "accountId" -> accountId = Long.parseLong(val);
                }
            }

            if (orderId == 0) return null;

            // Default to CLIENT_REQUEST if reason not specified
            final CancelReason cancelReason = (reason != null)
                    ? CancelReason.valueOf(reason)
                    : CancelReason.CLIENT_REQUEST;
            final long correlationId = correlationIdGenerator.getAndIncrement();

            return new CancelOrderRequest(correlationId, orderId, accountId, cancelReason);
        } catch (Exception e) {
            log.warn().append("[ingress] failed to parse cancel order: ").append(e.getMessage()).commit();
            return null;
        }
    }

    private AmendOrderRequest parseAmendOrder(String body) {
        try {
            long orderId = 0, accountId = 0;
            double newPrice = 0.0, newQuantity = 0.0;

            Matcher m = NUM_FIELD.matcher(body);
            while (m.find()) {
                final String val = m.group(2);
                switch (m.group(1)) {
                    case "orderId"      -> orderId      = Long.parseLong(val);
                    case "accountId"    -> accountId    = Long.parseLong(val);
                    case "newPrice"     -> newPrice     = Double.parseDouble(val);
                    case "newQuantity"  -> newQuantity  = Double.parseDouble(val);
                }
            }

            if (orderId == 0) return null;

            final long priceRaw    = (long)(newPrice    * 1e8);
            final long quantityRaw = (long)(newQuantity * 1e8);
            final long correlationId = correlationIdGenerator.getAndIncrement();

            return new AmendOrderRequest(correlationId, orderId, accountId, priceRaw, quantityRaw);
        } catch (Exception e) {
            log.warn().append("[ingress] failed to parse amend order: ").append(e.getMessage()).commit();
            return null;
        }
    }

    private static void sendJson(com.sun.net.httpserver.HttpExchange exchange,
                                 int status, String json) throws IOException {
        final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ── Request types ─────────────────────────────────────────────────────────

    sealed interface HttpRequest permits NewOrderRequest, CancelOrderRequest, AmendOrderRequest {}

    record NewOrderRequest(
            long correlationId,
            long orderId,
            long accountId,
            String instrument,
            Side side,
            OrderType orderType,
            TimeInForce timeInForce,
            long price,
            long quantity
    ) implements HttpRequest {}

    record CancelOrderRequest(
            long correlationId,
            long orderId,
            long accountId,
            CancelReason reason
    ) implements HttpRequest {}

    record AmendOrderRequest(
            long correlationId,
            long orderId,
            long accountId,
            long newPrice,
            long newQty
    ) implements HttpRequest {}
}
