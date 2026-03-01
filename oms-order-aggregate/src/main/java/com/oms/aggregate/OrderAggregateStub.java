package com.oms.aggregate;

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
 * Stub OrderAggregate — subscribes to the canonical Command Stream (StreamId 1) and
 * logs each received message's templateId and sequence number.
 *
 * TODO(POC): replace with full FSM-based aggregate in Milestone 2.
 */
public class OrderAggregateStub implements Agent {

    private static final Log log = LogFactory.getLog(OrderAggregateStub.class);

    // Reuse a single decoder instance — allocated once, never inside doWork().
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

    private final Subscription commandStreamSub;
    private final FragmentHandler fragmentHandler;

    public OrderAggregateStub(Subscription commandStreamSub) {
        this.commandStreamSub = commandStreamSub;
        // Allocate handler reference once — avoid lambda allocation on hot path.
        this.fragmentHandler = this::onFragment;
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
        // sequenceNumber is the first field after the 8-byte SBE MessageHeader.
        long seq = buffer.getLong(
                offset + MessageHeaderDecoder.ENCODED_LENGTH, ByteOrder.LITTLE_ENDIAN);

        log.info()
            .append("[aggregate] received templateId=").append(templateId)
            .append(" seq=").append(seq)
            .commit();
    }
}
