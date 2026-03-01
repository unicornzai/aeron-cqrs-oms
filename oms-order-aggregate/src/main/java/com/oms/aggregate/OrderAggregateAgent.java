package com.oms.aggregate;

import com.epam.deltix.gflog.api.Log;
import com.epam.deltix.gflog.api.LogFactory;
import com.oms.sbe.MessageHeaderDecoder;
import com.oms.sbe.MessageHeaderEncoder;
import com.oms.sbe.NewOrderCommandDecoder;
import com.oms.sbe.OrderAcceptedEventEncoder;
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
 * Subscribes to the sequenced Command Stream (StreamId 1), validates each NewOrderCommand,
 * and publishes either an OrderAcceptedEvent or OrderRejectedEvent to the Event Ingress
 * (StreamId 11) for the Sequencer to stamp and fan-out.
 *
 * <p>All state is single-threaded — only this agent's AgentRunner thread touches {@code orders}.
 */
public class OrderAggregateAgent implements Agent {

    private static final Log log = LogFactory.getLog(OrderAggregateAgent.class);

    // Pre-allocated encoding state — never allocate inside doWork()
    private final UnsafeBuffer encodingBuffer = new UnsafeBuffer(new byte[512]);
    private final MessageHeaderDecoder headerDecoder   = new MessageHeaderDecoder();
    private final MessageHeaderEncoder headerEncoder   = new MessageHeaderEncoder();
    private final NewOrderCommandDecoder   cmdDecoder  = new NewOrderCommandDecoder();
    private final OrderAcceptedEventEncoder acceptedEncoder = new OrderAcceptedEventEncoder();
    private final OrderRejectedEventEncoder rejectedEncoder = new OrderRejectedEventEncoder();

    private final Subscription commandStreamSub;
    private final Publication  eventIngressPub;
    private final FragmentHandler fragmentHandler;

    // In-memory order book — single-threaded, HashMap is fine
    private final Map<Long, OrderState> orders = new HashMap<>();

    public OrderAggregateAgent(Subscription commandStreamSub, Publication eventIngressPub) {
        this.commandStreamSub = commandStreamSub;
        this.eventIngressPub  = eventIngressPub;
        // Allocate handler reference once — avoid lambda re-allocation on hot path
        this.fragmentHandler  = this::onFragment;
    }

    @Override
    public int doWork() {
        return commandStreamSub.poll(fragmentHandler, 10);
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

    private void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
        headerDecoder.wrap(buffer, offset);
        int templateId = headerDecoder.templateId();

        if (templateId == NewOrderCommandDecoder.TEMPLATE_ID) {
            handleNewOrder(buffer, offset);
        }
        // TODO(POC): handle CancelOrderCommand (templateId=2), AmendOrderCommand (templateId=3)
    }

    private void handleNewOrder(DirectBuffer buffer, int offset) {
        // wrapAndApplyHeader re-wraps headerDecoder and positions the body decoder
        cmdDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        final long orderId      = cmdDecoder.orderId();
        final long accountId    = cmdDecoder.accountId();
        final long price        = cmdDecoder.price();
        final long quantity     = cmdDecoder.quantity();
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
        final String instrument = cmdDecoder.instrument();
        final Side side         = cmdDecoder.side();
        final OrderType orderType = cmdDecoder.orderType();
        final TimeInForce tif   = cmdDecoder.timeInForce();

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
}
