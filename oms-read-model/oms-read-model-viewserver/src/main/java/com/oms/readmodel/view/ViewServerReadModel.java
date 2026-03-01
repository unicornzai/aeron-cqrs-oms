package com.oms.readmodel.view;

import com.epam.deltix.gflog.api.Log;
import com.epam.deltix.gflog.api.LogFactory;
import com.oms.sbe.MessageHeaderDecoder;
import com.oms.sbe.OrderAcceptedEventDecoder;
import com.oms.sbe.OrderFilledEventDecoder;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.Agent;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Projects the sequenced Event Stream (StreamId 2) into an in-memory read model.
 *
 * <p>Each event is applied in sequence-number order (guaranteed by the Sequencer).
 * The {@code ConcurrentHashMap} allows a future HTTP/WebSocket thread to read
 * {@link #getOrders()} without blocking the AgentRunner thread.
 *
 * TODO(POC): push snapshots to WebSocket clients in Milestone 5.
 */
public class ViewServerReadModel implements Agent {

    private static final Log log = LogFactory.getLog(ViewServerReadModel.class);

    private final MessageHeaderDecoder      headerDecoder   = new MessageHeaderDecoder();
    private final OrderAcceptedEventDecoder acceptedDecoder = new OrderAcceptedEventDecoder();
    private final OrderFilledEventDecoder   filledDecoder   = new OrderFilledEventDecoder();

    private final Subscription eventStreamSub;
    private final FragmentHandler fragmentHandler;

    // ConcurrentHashMap so a future query thread can read without locking the agent thread
    private final ConcurrentHashMap<Long, OrderView> orders = new ConcurrentHashMap<>();

    public ViewServerReadModel(Subscription eventStreamSub) {
        this.eventStreamSub  = eventStreamSub;
        this.fragmentHandler = this::onFragment;
    }

    @Override
    public int doWork() {
        return eventStreamSub.poll(fragmentHandler, 10);
    }

    @Override
    public String roleName() { return "view-server"; }

    @Override
    public void onStart() {
        log.info().append("view-server started").commit();
    }

    @Override
    public void onClose() {
        log.info().append("view-server closed").commit();
    }

    /** Read-only view for query threads (M5 REST/WebSocket layer). */
    public Map<Long, OrderView> getOrders() {
        return Collections.unmodifiableMap(orders);
    }

    private void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
        headerDecoder.wrap(buffer, offset);
        final int templateId = headerDecoder.templateId();

        switch (templateId) {
            case OrderAcceptedEventDecoder.TEMPLATE_ID -> handleOrderAccepted(buffer, offset);
            case OrderFilledEventDecoder.TEMPLATE_ID   -> handleOrderFilled(buffer, offset);
            // TODO(POC): handle OrderRejectedEvent, OrderCancelledEvent, OrderAmendedEvent
        }
    }

    private void handleOrderAccepted(DirectBuffer buffer, int offset) {
        acceptedDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        final OrderView view = new OrderView(
                acceptedDecoder.orderId(),
                acceptedDecoder.accountId(),
                acceptedDecoder.instrument(),
                acceptedDecoder.side().name(),
                acceptedDecoder.orderType().name(),
                acceptedDecoder.timeInForce().name(),
                acceptedDecoder.price(),
                acceptedDecoder.quantity(),
                OrderStatus.OPEN);

        orders.put(view.orderId, view);
        log.info().append("[view-server] ").append(view.toString()).commit();
    }

    private void handleOrderFilled(DirectBuffer buffer, int offset) {
        filledDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        final long orderId = filledDecoder.orderId();
        final OrderView existing = orders.get(orderId);
        if (existing == null) {
            log.warn()
                .append("[view-server] OrderFilledEvent for unknown orderId=").append(orderId)
                .commit();
            return;
        }

        final OrderView updated = existing.withFill(filledDecoder.fillPrice(), filledDecoder.fillQuantity());
        orders.put(orderId, updated);
        log.info().append("[view-server] ").append(updated.toString()).commit();
    }
}
