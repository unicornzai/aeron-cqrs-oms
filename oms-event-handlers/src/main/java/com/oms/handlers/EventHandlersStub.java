package com.oms.handlers;

import com.epam.deltix.gflog.api.Log;
import com.epam.deltix.gflog.api.LogFactory;
import com.oms.sbe.MessageHeaderDecoder;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.Agent;

import java.nio.ByteOrder;

/**
 * Stub EventHandlers — subscribes to the canonical Event Stream (StreamId 2) and
 * logs each event's templateId and sequence number.
 *
 * Represents the three handlers from the design (FillSimulator, RiskEventHandler,
 * CancelOnRiskBreachHandler) consolidated into a single stub for Milestone 1.
 *
 * TODO(POC): split into individual stateless handlers in Milestone 2.
 */
public class EventHandlersStub implements Agent {

    private static final Log log = LogFactory.getLog(EventHandlersStub.class);

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

    private final Subscription eventStreamSub;
    private final FragmentHandler fragmentHandler;

    public EventHandlersStub(Subscription eventStreamSub) {
        this.eventStreamSub  = eventStreamSub;
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
        int templateId = headerDecoder.templateId();
        long seq = buffer.getLong(
                offset + MessageHeaderDecoder.ENCODED_LENGTH, ByteOrder.LITTLE_ENDIAN);

        log.info()
            .append("[event-handler] received templateId=").append(templateId)
            .append(" seq=").append(seq)
            .commit();
    }
}
