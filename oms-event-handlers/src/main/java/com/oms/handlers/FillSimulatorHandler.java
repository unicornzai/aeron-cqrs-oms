package com.oms.handlers;

import com.epam.deltix.gflog.api.Log;
import com.epam.deltix.gflog.api.LogFactory;
import com.oms.sbe.MessageHeaderDecoder;
import com.oms.sbe.MessageHeaderEncoder;
import com.oms.sbe.OrderAcceptedEventDecoder;
import com.oms.sbe.OrderFilledEventEncoder;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Subscribes to the sequenced Event Stream (StreamId 2) and immediately fills every
 * OrderAcceptedEvent with a full fill at the limit price.
 *
 * <p>Publishes OrderFilledEvent to Event Ingress (StreamId 11) so the Sequencer
 * can stamp and fan-out to all downstream read models.
 *
 * TODO(POC): replace with a real matching engine in a later milestone.
 *            For now this simulates immediate full fill at the order price.
 */
public class FillSimulatorHandler implements Agent {

    private static final Log log = LogFactory.getLog(FillSimulatorHandler.class);

    // Pre-allocated — never allocate inside doWork()
    private final UnsafeBuffer encodingBuffer  = new UnsafeBuffer(new byte[512]);
    private final MessageHeaderDecoder headerDecoder   = new MessageHeaderDecoder();
    private final MessageHeaderEncoder headerEncoder   = new MessageHeaderEncoder();
    private final OrderAcceptedEventDecoder acceptedDecoder = new OrderAcceptedEventDecoder();
    private final OrderFilledEventEncoder   filledEncoder   = new OrderFilledEventEncoder();

    private final Subscription eventStreamSub;
    private final Publication  eventIngressPub;
    private final FragmentHandler fragmentHandler;

    public FillSimulatorHandler(Subscription eventStreamSub, Publication eventIngressPub) {
        this.eventStreamSub  = eventStreamSub;
        this.eventIngressPub = eventIngressPub;
        this.fragmentHandler = this::onFragment;
    }

    @Override
    public int doWork() {
        return eventStreamSub.poll(fragmentHandler, 10);
    }

    @Override
    public String roleName() { return "event-handlers"; }

    @Override
    public void onStart() {
        log.info().append("event-handlers started").commit();
    }

    @Override
    public void onClose() {
        log.info().append("event-handlers closed").commit();
    }

    private void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
        headerDecoder.wrap(buffer, offset);
        final int templateId = headerDecoder.templateId();

        if (templateId == OrderAcceptedEventDecoder.TEMPLATE_ID) {
            handleOrderAccepted(buffer, offset);
        }
        // Other event types (rejected, cancelled, etc.) are intentionally ignored here
    }

    private void handleOrderAccepted(DirectBuffer buffer, int offset) {
        acceptedDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        final long orderId       = acceptedDecoder.orderId();
        final long accountId     = acceptedDecoder.accountId();
        final long correlationId = acceptedDecoder.correlationId();
        // Immediate full fill at the limit price — POC-acceptable
        final long fillPrice    = acceptedDecoder.price();
        final long fillQuantity = acceptedDecoder.quantity();

        filledEncoder.wrapAndApplyHeader(encodingBuffer, 0, headerEncoder)
                .sequenceNumber(0)   // Sequencer overwrites this
                .timestamp(System.nanoTime())
                .correlationId(correlationId)
                .orderId(orderId)
                .accountId(accountId)
                .fillPrice(fillPrice)
                .fillQuantity(fillQuantity);

        final int msgLen = MessageHeaderEncoder.ENCODED_LENGTH + OrderFilledEventEncoder.BLOCK_LENGTH;
        final long result = eventIngressPub.offer(encodingBuffer, 0, msgLen);
        if (result > 0) {
            log.info()
                .append("[event-handlers] (FillSimulator) OrderAcceptedEvent orderId=").append(orderId)
                .append(" → publishing OrderFilledEvent fillPrice=").append(fillPrice)
                .append(" fillQty=").append(fillQuantity)
                .commit();
        } else {
            // TODO(POC): add back-pressure retry budget
            log.warn()
                .append("[event-handlers] failed to publish OrderFilledEvent orderId=").append(orderId)
                .append(" result=").append(result)
                .commit();
        }
    }
}
