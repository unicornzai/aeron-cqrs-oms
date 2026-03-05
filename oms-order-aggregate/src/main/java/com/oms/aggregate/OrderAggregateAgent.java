package com.oms.aggregate;

import com.epam.deltix.gflog.api.Log;
import com.epam.deltix.gflog.api.LogFactory;
import com.oms.fix.sbe.PlaceOrderCommandDecoder;
import com.oms.sbe.AmendOrderCommandDecoder;
import com.oms.sbe.CancelOrderCommandDecoder;
import com.oms.sbe.CancelReason;
import com.oms.sbe.CancelRejectedEventEncoder;
import com.oms.sbe.MessageHeaderDecoder;
import com.oms.sbe.MessageHeaderEncoder;
import com.oms.sbe.NewOrderCommandDecoder;
import com.oms.sbe.OrderAcceptedEventDecoder;
import com.oms.sbe.OrderAcceptedEventEncoder;
import com.oms.sbe.OrderAmendedEventDecoder;
import com.oms.sbe.OrderAmendedEventEncoder;
import com.oms.sbe.OrderCancelledEventDecoder;
import com.oms.sbe.OrderCancelledEventEncoder;
import com.oms.sbe.OrderFilledEventDecoder;
import com.oms.sbe.OrderPartiallyFilledEventDecoder;
import com.oms.sbe.OrderRejectedEventEncoder;
import com.oms.sbe.OrderType;
import com.oms.sbe.RejectReason;
import com.oms.sbe.Side;
import com.oms.sbe.TimeInForce;
import com.oms.common.OmsStreams;
import io.aeron.Aeron;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.UnsafeBuffer;

import java.util.HashMap;
import java.util.Map;

/**
 * Subscribes to the sequenced Command Stream (StreamId 1) and Event Stream (StreamId 2).
 * Validates commands, maintains order state, and publishes events to Event Ingress (StreamId 11).
 *
 * <p>On startup, replays the full Event Stream from Archive to rebuild the in-memory
 * {@code orders} map before accepting any live commands. This ensures correct duplicate
 * detection and state-machine transitions after a process restart.
 *
 * <p>Observes fill events from the live Event Stream to guard cancel/amend on FILLED orders.
 * All state is single-threaded — only this agent's AgentRunner thread touches {@code orders}.
 *
 * TODO(POC): Archive replay blocks onStart() — live commands buffer in Aeron term buffer.
 *            Size term buffer based on max replay duration × msg rate.
 */
public class OrderAggregateAgent implements Agent {

    private static final Log log = LogFactory.getLog(OrderAggregateAgent.class);

    private static final int  AGGREGATE_REPLAY_STREAM_ID = 20;
    private static final long REPLAY_TIMEOUT_MS          = 10_000L;

    // Pre-allocated encoding state — never allocate inside doWork()
    private final UnsafeBuffer encodingBuffer = new UnsafeBuffer(new byte[512]);
    private final MessageHeaderDecoder headerDecoder    = new MessageHeaderDecoder();
    // Separate fix-sbe header decoder for PlaceOrderCommand cross-schema wrapAndApplyHeader.
    // Both schemas use identical 8-byte header wire format; the type must match the encoder used.
    private final com.oms.fix.sbe.MessageHeaderDecoder fixHeaderDecoder =
        new com.oms.fix.sbe.MessageHeaderDecoder();
    private final PlaceOrderCommandDecoder placeOrderCmdDecoder = new PlaceOrderCommandDecoder();
    private final MessageHeaderEncoder headerEncoder    = new MessageHeaderEncoder();
    private final NewOrderCommandDecoder     cmdDecoder        = new NewOrderCommandDecoder();
    private final CancelOrderCommandDecoder  cancelCmdDecoder  = new CancelOrderCommandDecoder();
    private final AmendOrderCommandDecoder   amendCmdDecoder   = new AmendOrderCommandDecoder();
    private final OrderFilledEventDecoder    filledDecoder     = new OrderFilledEventDecoder();
    private final OrderPartiallyFilledEventDecoder partialDecoder = new OrderPartiallyFilledEventDecoder();
    private final OrderAcceptedEventEncoder  acceptedEncoder   = new OrderAcceptedEventEncoder();
    private final OrderRejectedEventEncoder  rejectedEncoder   = new OrderRejectedEventEncoder();
    private final OrderCancelledEventEncoder cancelledEncoder  = new OrderCancelledEventEncoder();
    private final OrderAmendedEventEncoder   amendedEncoder    = new OrderAmendedEventEncoder();
    private final CancelRejectedEventEncoder cancelRejEncoder  = new CancelRejectedEventEncoder();

    // Pre-allocated replay decoders — reused across all replayed messages
    private final OrderAcceptedEventDecoder  replayAcceptedDecoder  = new OrderAcceptedEventDecoder();
    private final OrderCancelledEventDecoder replayCancelledDecoder = new OrderCancelledEventDecoder();
    private final OrderAmendedEventDecoder   replayAmendedDecoder   = new OrderAmendedEventDecoder();

    private final Subscription commandStreamSub;
    private final Subscription eventStreamSub;
    private final Publication  eventIngressPub;
    private final FragmentHandler commandHandler;
    private final FragmentHandler eventHandler;
    private final Aeron        aeron;
    private final AeronArchive archive;

    // In-memory order book — single-threaded, HashMap is fine
    private final Map<Long, OrderState> orders = new HashMap<>();

    public OrderAggregateAgent(Subscription commandStreamSub, Publication eventIngressPub,
                               Subscription eventStreamSub, Aeron aeron, AeronArchive archive) {
        this.commandStreamSub = commandStreamSub;
        this.eventIngressPub  = eventIngressPub;
        this.eventStreamSub   = eventStreamSub;
        this.aeron            = aeron;
        this.archive          = archive;
        // Allocate handler references once — avoid lambda re-allocation on hot path
        this.commandHandler   = this::onCommandFragment;
        this.eventHandler     = this::onEventFragment;
    }

    @Override
    public int doWork() {
        int work = commandStreamSub.poll(commandHandler, 10);
        work += eventStreamSub.poll(eventHandler, 10);
        return work;
    }

    @Override
    public String roleName() { return "order-aggregate"; }

    /**
     * Replays the full Event Stream from Archive position 0 to the recorded stop/live position.
     * Blocks until replay image closes (i.e., Archive has delivered all requested bytes).
     * Live commands are safely buffered in the Aeron term buffer during replay.
     */
    @Override
    public void onStart() {
        log.info().append("[aggregate] starting — replaying Event Stream from Archive...").commit();

        final long[] foundRecordingId  = {-1L};
        final long[] foundStopPosition = {AeronArchive.NULL_POSITION};

        final int found = archive.listRecordingsForUri(
                0, 1, OmsStreams.IPC, OmsStreams.EVENT_STREAM,
                (controlSessionId, correlationId, recordingId, startTimestamp, stopTimestamp,
                 startPosition, stopPosition, initialTermId, segmentFileLength, termBufferLength,
                 mtuLength, sessionId, streamId, strippedChannel, originalChannel, sourceIdentity) -> {
                    foundRecordingId[0]  = recordingId;
                    foundStopPosition[0] = stopPosition;  // NULL_POSITION if still active
                });

        if (found == 0 || foundRecordingId[0] < 0) {
            log.info().append("[aggregate] no recording found — starting fresh").commit();
            return;
        }

        final long recordingId = foundRecordingId[0];

        // For a stopped recording (restart case), stopPosition has the persisted end position.
        // For an active recording (same boot), fall back to the live position.
        long currentPos = foundStopPosition[0];
        if (currentPos == AeronArchive.NULL_POSITION) {
            currentPos = archive.getRecordingPosition(recordingId);
        }

        if (currentPos <= 0) {
            log.info().append("[aggregate] recording empty — starting fresh").commit();
            return;
        }

        log.info().append("[aggregate] replaying recordingId=").append(recordingId)
            .append(" length=").append(currentPos).commit();

        final long replaySessionId = archive.startReplay(
                recordingId, 0L, currentPos, OmsStreams.IPC, AGGREGATE_REPLAY_STREAM_ID);

        try (final Subscription replaySub = aeron.addSubscription(
                     OmsStreams.IPC, AGGREGATE_REPLAY_STREAM_ID)) {

            final long deadlineMs = System.currentTimeMillis() + REPLAY_TIMEOUT_MS;
            Image replayImage = null;
            while (replayImage == null) {
                replayImage = replaySub.imageBySessionId((int) replaySessionId);
                if (System.currentTimeMillis() > deadlineMs) {
                    throw new IllegalStateException("[aggregate] replay image connect timeout");
                }
                Thread.yield();
            }

            // Poll until the image closes — Archive closes it after delivering currentPos bytes.
            while (!replayImage.isClosed()) {
                final int frags = replayImage.poll(this::applyReplayEvent, 256);
                if (frags == 0) Thread.yield();
            }
        }

        log.info().append("[aggregate] replay complete — orders rebuilt=").append(orders.size()).commit();
    }

    @Override
    public void onClose() {
        log.info().append("order-aggregate closed").commit();
    }

    // ── Replay handler ────────────────────────────────────────────────────────

    /**
     * Applies archived Event Stream messages to reconstruct the in-memory {@code orders} map.
     * Mirrors the live event observer but without any publishing or logging.
     * Called only during {@link #onStart()} replay — never on the live hot path.
     */
    private void applyReplayEvent(DirectBuffer buffer, int offset, int length, Header header) {
        headerDecoder.wrap(buffer, offset);
        switch (headerDecoder.templateId()) {
            case OrderAcceptedEventDecoder.TEMPLATE_ID -> {
                replayAcceptedDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                final long orderId = replayAcceptedDecoder.orderId();
                orders.computeIfAbsent(orderId, id -> new OrderState(
                        id, replayAcceptedDecoder.accountId(), replayAcceptedDecoder.instrument(),
                        replayAcceptedDecoder.side(), replayAcceptedDecoder.orderType(),
                        replayAcceptedDecoder.timeInForce(),
                        replayAcceptedDecoder.price(), replayAcceptedDecoder.quantity(),
                        OrderStatus.OPEN));
            }
            case OrderCancelledEventDecoder.TEMPLATE_ID -> {
                replayCancelledDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                final OrderState s = orders.get(replayCancelledDecoder.orderId());
                if (s != null) s.status = OrderStatus.CANCELLED;
            }
            case OrderAmendedEventDecoder.TEMPLATE_ID -> {
                replayAmendedDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                final OrderState s = orders.get(replayAmendedDecoder.orderId());
                if (s != null) {
                    s.currentPrice    = replayAmendedDecoder.newPrice();
                    s.currentQuantity = replayAmendedDecoder.newQuantity();
                }
            }
            case OrderFilledEventDecoder.TEMPLATE_ID -> {
                filledDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                final OrderState s = orders.get(filledDecoder.orderId());
                if (s != null) {
                    s.filledQuantity += filledDecoder.fillQuantity();
                    s.status = OrderStatus.FILLED;
                }
            }
            case OrderPartiallyFilledEventDecoder.TEMPLATE_ID -> {
                partialDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                final OrderState s = orders.get(partialDecoder.orderId());
                if (s != null) {
                    s.filledQuantity += partialDecoder.fillQuantity();
                    s.status = OrderStatus.PARTIALLY_FILLED;
                }
            }
            // OrderRejectedEvent(101), CancelRejectedEvent(106) — no state to rebuild, skip
        }
    }

    // ── Command stream handler ────────────────────────────────────────────────

    private void onCommandFragment(DirectBuffer buffer, int offset, int length, Header header) {
        headerDecoder.wrap(buffer, offset);
        final int templateId = headerDecoder.templateId();

        if (templateId == NewOrderCommandDecoder.TEMPLATE_ID) {
            handleNewOrder(buffer, offset);
        } else if (templateId == CancelOrderCommandDecoder.TEMPLATE_ID) {
            handleCancelOrder(buffer, offset);
        } else if (templateId == AmendOrderCommandDecoder.TEMPLATE_ID) {
            handleAmendOrder(buffer, offset);
        } else if (templateId == PlaceOrderCommandDecoder.TEMPLATE_ID) {
            // PlaceOrderCommand (fix-sbe, templateId=20) — emitted by FixOrderAggregateAgent
            // after translating a FIX NewOrderSingle. Shares the sequenced Command Stream.
            handlePlaceOrder(buffer, offset);
        }
    }

    // ── Event stream observer (state tracking only, no publishing) ────────────

    private void onEventFragment(DirectBuffer buffer, int offset, int length, Header header) {
        headerDecoder.wrap(buffer, offset);
        final int templateId = headerDecoder.templateId();

        if (templateId == OrderFilledEventDecoder.TEMPLATE_ID) {
            filledDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
            final long orderId = filledDecoder.orderId();
            final OrderState state = orders.get(orderId);
            if (state != null) {
                state.filledQuantity += filledDecoder.fillQuantity();
                state.status = OrderStatus.FILLED;
                log.info()
                    .append("[aggregate] orderId=").append(orderId)
                    .append(" → FILLED")
                    .commit();
            }
        } else if (templateId == OrderPartiallyFilledEventDecoder.TEMPLATE_ID) {
            partialDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
            final long orderId = partialDecoder.orderId();
            final OrderState state = orders.get(orderId);
            if (state != null) {
                state.filledQuantity += partialDecoder.fillQuantity();
                state.status = OrderStatus.PARTIALLY_FILLED;
                log.info()
                    .append("[aggregate] orderId=").append(orderId)
                    .append(" → PARTIALLY_FILLED filledQty=").append(state.filledQuantity)
                    .commit();
            }
        }
    }

    // ── Command handlers ──────────────────────────────────────────────────────

    private void handleNewOrder(DirectBuffer buffer, int offset) {
        // wrapAndApplyHeader re-wraps headerDecoder and positions the body decoder
        cmdDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        final long orderId       = cmdDecoder.orderId();
        final long accountId     = cmdDecoder.accountId();
        final long price         = cmdDecoder.price();
        final long quantity      = cmdDecoder.quantity();
        final long correlationId = cmdDecoder.correlationId();

        // Validation — reject path
        if (price <= 0) {
            publishReject(orderId, accountId, correlationId, RejectReason.INVALID_PRICE);
            return;
        }
        if (quantity <= 0) {
            publishReject(orderId, accountId, correlationId, RejectReason.INVALID_QUANTITY);
            return;
        }
        if (orders.containsKey(orderId)) {
            publishReject(orderId, accountId, correlationId, RejectReason.DUPLICATE_ORDER);
            return;
        }

        // Accept path — copy all fields from command into the event
        final String instrument   = cmdDecoder.instrument();
        final Side side           = cmdDecoder.side();
        final OrderType orderType = cmdDecoder.orderType();
        final TimeInForce tif     = cmdDecoder.timeInForce();

        acceptedEncoder.wrapAndApplyHeader(encodingBuffer, 0, headerEncoder)
                .sequenceNumber(0)   // Sequencer overwrites this
                .timestamp(System.nanoTime())
                .correlationId(correlationId)
                .orderId(orderId)
                .accountId(accountId)
                .instrument(instrument)
                .side(side)
                .orderType(orderType)
                .timeInForce(tif)
                .price(price)
                .quantity(quantity);

        final int msgLen = MessageHeaderEncoder.ENCODED_LENGTH + OrderAcceptedEventEncoder.BLOCK_LENGTH;
        final long result = eventIngressPub.offer(encodingBuffer, 0, msgLen);
        if (result > 0) {
            orders.put(orderId, new OrderState(
                    orderId, accountId, instrument, side, orderType, tif, price, quantity, OrderStatus.OPEN));
            log.info()
                .append("[aggregate] accepted orderId=").append(orderId)
                .append(" status=OPEN")
                .commit();
        } else {
            // TODO(POC): add back-pressure retry budget
            log.warn()
                .append("[aggregate] failed to publish OrderAcceptedEvent orderId=").append(orderId)
                .append(" result=").append(result)
                .commit();
        }
    }

    private void handleCancelOrder(DirectBuffer buffer, int offset) {
        cancelCmdDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        final long orderId       = cancelCmdDecoder.orderId();
        final long accountId     = cancelCmdDecoder.accountId();
        final long correlationId = cancelCmdDecoder.correlationId();
        final CancelReason reason = cancelCmdDecoder.cancelReason();

        final OrderState state = orders.get(orderId);
        if (state == null) {
            log.warn()
                .append("[aggregate] cancel for unknown orderId=").append(orderId)
                .commit();
            return;
        }

        // Invalid terminal states — reject the cancel
        if (state.status == OrderStatus.FILLED
                || state.status == OrderStatus.CANCELLED
                || state.status == OrderStatus.REJECTED) {
            // TODO(POC): add ORDER_ALREADY_FILLED / ORDER_ALREADY_CANCELLED reason to RejectReason schema
            publishCancelRejected(orderId, correlationId, RejectReason.RISK_BREACH);
            return;
        }

        // OPEN or PARTIALLY_FILLED — allow cancel
        cancelledEncoder.wrapAndApplyHeader(encodingBuffer, 0, headerEncoder)
                .sequenceNumber(0)
                .timestamp(System.nanoTime())
                .correlationId(correlationId)
                .orderId(orderId)
                .accountId(accountId)
                .cancelReason(reason);

        final int msgLen = MessageHeaderEncoder.ENCODED_LENGTH + OrderCancelledEventEncoder.BLOCK_LENGTH;
        final long result = eventIngressPub.offer(encodingBuffer, 0, msgLen);
        if (result > 0) {
            state.status = OrderStatus.CANCELLED;
            log.info()
                .append("[aggregate] cancelled orderId=").append(orderId)
                .append(" → CANCELLED")
                .commit();
        } else {
            // TODO(POC): add back-pressure retry budget
            log.warn()
                .append("[aggregate] failed to publish OrderCancelledEvent orderId=").append(orderId)
                .append(" result=").append(result)
                .commit();
        }
    }

    private void handleAmendOrder(DirectBuffer buffer, int offset) {
        amendCmdDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        final long orderId       = amendCmdDecoder.orderId();
        final long accountId     = amendCmdDecoder.accountId();
        final long correlationId = amendCmdDecoder.correlationId();
        final long newPrice      = amendCmdDecoder.newPrice();
        final long newQuantity   = amendCmdDecoder.newQuantity();

        final OrderState state = orders.get(orderId);
        if (state == null) {
            log.warn()
                .append("[aggregate] amend for unknown orderId=").append(orderId)
                .commit();
            return;
        }

        if (state.status != OrderStatus.OPEN && state.status != OrderStatus.PARTIALLY_FILLED) {
            log.warn()
                .append("[aggregate] amend rejected orderId=").append(orderId)
                .append(" status=").append(state.status.name())
                .commit();
            return;
        }

        if (newPrice <= 0) {
            log.warn()
                .append("[aggregate] amend rejected orderId=").append(orderId)
                .append(" invalid newPrice=").append(newPrice)
                .commit();
            return;
        }

        if (newQuantity <= state.filledQuantity) {
            log.warn()
                .append("[aggregate] amend rejected orderId=").append(orderId)
                .append(" newQty=").append(newQuantity)
                .append(" not above filledQty=").append(state.filledQuantity)
                .commit();
            return;
        }

        amendedEncoder.wrapAndApplyHeader(encodingBuffer, 0, headerEncoder)
                .sequenceNumber(0)
                .timestamp(System.nanoTime())
                .correlationId(correlationId)
                .orderId(orderId)
                .accountId(accountId)
                .newPrice(newPrice)
                .newQuantity(newQuantity);

        final int msgLen = MessageHeaderEncoder.ENCODED_LENGTH + OrderAmendedEventEncoder.BLOCK_LENGTH;
        final long result = eventIngressPub.offer(encodingBuffer, 0, msgLen);
        if (result > 0) {
            state.currentPrice    = newPrice;
            state.currentQuantity = newQuantity;
            log.info()
                .append("[aggregate] amended orderId=").append(orderId)
                .append(" newPrice=").append(newPrice)
                .append(" newQty=").append(newQuantity)
                .commit();
        } else {
            // TODO(POC): add back-pressure retry budget
            log.warn()
                .append("[aggregate] failed to publish OrderAmendedEvent orderId=").append(orderId)
                .append(" result=").append(result)
                .commit();
        }
    }

    // ── FIX PlaceOrderCommand handler ─────────────────────────────────────────

    /**
     * Handles {@code PlaceOrderCommand} (fix-sbe templateId=20) arriving on the sequenced
     * Command Stream after being translated from a FIX NewOrderSingle by
     * {@link com.oms.fix.aggregate.FixOrderAggregateAgent}.
     *
     * <p>Validates symbol non-empty, qty > 0, price > 0 for LIMIT orders.
     * On success emits {@code OrderAcceptedEvent}; on failure emits {@code OrderRejectedEvent}.
     *
     * TODO(POC): accountId=0 and TimeInForce.DAY are defaults — PlaceOrderCommand has no acct/TIF
     */
    private void handlePlaceOrder(DirectBuffer buffer, int offset) {
        // wrapAndApplyHeader requires the fix-sbe header decoder — same wire format, different type
        placeOrderCmdDecoder.wrapAndApplyHeader(buffer, offset, fixHeaderDecoder);

        final long   orderId    = placeOrderCmdDecoder.orderId();
        final String rawSymbol  = placeOrderCmdDecoder.symbol();
        // SBE char arrays may be NUL/space padded — strip trailing garbage
        int end = rawSymbol.length();
        while (end > 0 && (rawSymbol.charAt(end - 1) == '\0' || rawSymbol.charAt(end - 1) == ' ')) end--;
        final String symbol = rawSymbol.substring(0, end);

        final com.oms.fix.sbe.SideEnum   fixSide    = placeOrderCmdDecoder.side();
        final com.oms.fix.sbe.OrdTypeEnum fixOrdType = placeOrderCmdDecoder.ordType();

        final long priceFixed8 = decimal64ToFixed8(
            placeOrderCmdDecoder.price().mantissa(), placeOrderCmdDecoder.price().exponent());
        final long qtyFixed8   = decimal64ToFixed8(
            placeOrderCmdDecoder.orderQty().mantissa(), placeOrderCmdDecoder.orderQty().exponent());

        // Validation
        if (symbol.isEmpty()) {
            publishReject(orderId, 0L, 0L, RejectReason.UNKNOWN_INSTRUMENT);
            return;
        }
        if (qtyFixed8 <= 0) {
            publishReject(orderId, 0L, 0L, RejectReason.INVALID_QUANTITY);
            return;
        }
        if (fixOrdType == com.oms.fix.sbe.OrdTypeEnum.LIMIT && priceFixed8 <= 0) {
            publishReject(orderId, 0L, 0L, RejectReason.INVALID_PRICE);
            return;
        }
        if (orders.containsKey(orderId)) {
            publishReject(orderId, 0L, 0L, RejectReason.DUPLICATE_ORDER);
            return;
        }

        // Map fix-sbe enums → oms-sbe enums (both use 0=BUY/LIMIT, 1=SELL/MARKET)
        final Side      side      = fixSide    == com.oms.fix.sbe.SideEnum.BUY     ? Side.BUY      : Side.SELL;
        final OrderType orderType = fixOrdType == com.oms.fix.sbe.OrdTypeEnum.LIMIT ? OrderType.LIMIT : OrderType.MARKET;

        // Fit symbol (up to 20 chars) into instrument field (12 chars) — truncate if needed
        final String instrument = symbol.length() > 12 ? symbol.substring(0, 12) : symbol;

        acceptedEncoder.wrapAndApplyHeader(encodingBuffer, 0, headerEncoder)
            .sequenceNumber(0)           // Sequencer overwrites this
            .timestamp(System.nanoTime())
            .correlationId(0L)           // TODO(POC): no correlationId in PlaceOrderCommand
            .orderId(orderId)
            .accountId(0L)               // TODO(POC): no accountId in PlaceOrderCommand
            .instrument(instrument)
            .side(side)
            .orderType(orderType)
            .timeInForce(TimeInForce.DAY) // TODO(POC): no TIF in PlaceOrderCommand — default DAY
            .price(priceFixed8)
            .quantity(qtyFixed8);

        final int msgLen = MessageHeaderEncoder.ENCODED_LENGTH + OrderAcceptedEventEncoder.BLOCK_LENGTH;
        final long result = eventIngressPub.offer(encodingBuffer, 0, msgLen);
        if (result > 0) {
            orders.put(orderId, new OrderState(
                orderId, 0L, instrument, side, orderType, TimeInForce.DAY,
                priceFixed8, qtyFixed8, OrderStatus.OPEN));
            log.info()
                .append("[aggregate] FIX PlaceOrder accepted orderId=").append(orderId)
                .append(" symbol=").append(instrument)
                .append(" qty=").append(qtyFixed8)
                .append(" price=").append(priceFixed8)
                .commit();
        } else {
            log.warn()
                .append("[aggregate] failed to publish OrderAcceptedEvent for PlaceOrder orderId=").append(orderId)
                .append(" result=").append(result)
                .commit();
        }
    }

    /**
     * Converts a {@code Decimal64} (mantissa × 10^exponent) to the OMS fixed-point
     * representation (value × 10^8).
     *
     * <p>result = mantissa × 10^(8+exponent)
     *
     * <p>Common case: {@code FixSessionHandler} encodes prices with {@code exponent = -scale},
     * e.g. 185.50 → mantissa=18550, exponent=-2 → fixed8 = 18550 × 10^6 = 18_550_000_000.
     * For qty with scale=0: mantissa=100, exponent=0 → fixed8 = 100 × 10^8 = 10_000_000_000.
     *
     * <p>Special case exponent=-8 (set by some encoders): shift=0 → identity, result=mantissa.
     */
    private static long decimal64ToFixed8(final long mantissa, final byte exponent) {
        final int shift = 8 + exponent;
        if (shift == 0) return mantissa;
        if (shift > 0) {
            long result = mantissa;
            for (int i = 0; i < shift; i++) result *= 10;
            return result;
        } else {
            long result = mantissa;
            for (int i = 0; i < -shift; i++) result /= 10;
            return result;
        }
    }

    // ── Event publishers ──────────────────────────────────────────────────────

    private void publishReject(long orderId, long accountId, long correlationId, RejectReason reason) {
        rejectedEncoder.wrapAndApplyHeader(encodingBuffer, 0, headerEncoder)
                .sequenceNumber(0)
                .timestamp(System.nanoTime())
                .correlationId(correlationId)
                .orderId(orderId)
                .accountId(accountId)
                .rejectReason(reason);

        final int msgLen = MessageHeaderEncoder.ENCODED_LENGTH + OrderRejectedEventEncoder.BLOCK_LENGTH;
        final long result = eventIngressPub.offer(encodingBuffer, 0, msgLen);
        if (result > 0) {
            log.info()
                .append("[aggregate] rejected orderId=").append(orderId)
                .append(" reason=").append(reason.name())
                .commit();
        } else {
            log.warn()
                .append("[aggregate] failed to publish OrderRejectedEvent orderId=").append(orderId)
                .commit();
        }
    }

    private void publishCancelRejected(long orderId, long correlationId, RejectReason reason) {
        cancelRejEncoder.wrapAndApplyHeader(encodingBuffer, 0, headerEncoder)
                .sequenceNumber(0)
                .timestamp(System.nanoTime())
                .correlationId(correlationId)
                .orderId(orderId)
                .rejectReason(reason);

        final int msgLen = MessageHeaderEncoder.ENCODED_LENGTH + CancelRejectedEventEncoder.BLOCK_LENGTH;
        final long result = eventIngressPub.offer(encodingBuffer, 0, msgLen);
        if (result > 0) {
            log.info()
                .append("[aggregate] cancel rejected orderId=").append(orderId)
                .append(" reason=").append(reason.name())
                .commit();
        } else {
            log.warn()
                .append("[aggregate] failed to publish CancelRejectedEvent orderId=").append(orderId)
                .commit();
        }
    }
}
