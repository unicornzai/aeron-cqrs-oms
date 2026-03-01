package com.oms.aggregate;

import com.epam.deltix.gflog.api.Log;
import com.epam.deltix.gflog.api.LogFactory;
import com.oms.sbe.AmendOrderCommandDecoder;
import com.oms.sbe.CancelOrderCommandDecoder;
import com.oms.sbe.CancelReason;
import com.oms.sbe.CancelRejectedEventEncoder;
import com.oms.sbe.MessageHeaderDecoder;
import com.oms.sbe.MessageHeaderEncoder;
import com.oms.sbe.NewOrderCommandDecoder;
import com.oms.sbe.OrderAcceptedEventEncoder;
import com.oms.sbe.OrderAmendedEventEncoder;
import com.oms.sbe.OrderCancelledEventEncoder;
import com.oms.sbe.OrderFilledEventDecoder;
import com.oms.sbe.OrderPartiallyFilledEventDecoder;
import com.oms.sbe.OrderRejectedEventEncoder;
import com.oms.sbe.OrderType;
import com.oms.sbe.RejectReason;
import com.oms.sbe.Side;
import com.oms.sbe.TimeInForce;
import io.aeron.Publication;
import io.aeron.Subscription;
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
 * <p>Observes fill events from the Event Stream to guard cancel/amend on FILLED orders.
 * All state is single-threaded — only this agent's AgentRunner thread touches {@code orders}.
 */
public class OrderAggregateAgent implements Agent {

    private static final Log log = LogFactory.getLog(OrderAggregateAgent.class);

    // Pre-allocated encoding state — never allocate inside doWork()
    private final UnsafeBuffer encodingBuffer = new UnsafeBuffer(new byte[512]);
    private final MessageHeaderDecoder headerDecoder    = new MessageHeaderDecoder();
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

    private final Subscription commandStreamSub;
    private final Subscription eventStreamSub;
    private final Publication  eventIngressPub;
    private final FragmentHandler commandHandler;
    private final FragmentHandler eventHandler;

    // In-memory order book — single-threaded, HashMap is fine
    private final Map<Long, OrderState> orders = new HashMap<>();

    public OrderAggregateAgent(Subscription commandStreamSub, Publication eventIngressPub,
                               Subscription eventStreamSub) {
        this.commandStreamSub = commandStreamSub;
        this.eventIngressPub  = eventIngressPub;
        this.eventStreamSub   = eventStreamSub;
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

    @Override
    public void onStart() {
        log.info().append("order-aggregate started").commit();
    }

    @Override
    public void onClose() {
        log.info().append("order-aggregate closed").commit();
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
