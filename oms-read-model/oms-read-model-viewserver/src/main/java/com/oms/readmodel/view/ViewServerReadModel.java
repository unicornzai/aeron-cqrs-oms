package com.oms.readmodel.view;

import com.epam.deltix.gflog.api.Log;
import com.epam.deltix.gflog.api.LogFactory;
import com.oms.sbe.CancelRejectedEventDecoder;
import com.oms.sbe.MessageHeaderDecoder;
import com.oms.sbe.OrderAcceptedEventDecoder;
import com.oms.sbe.OrderAmendedEventDecoder;
import com.oms.sbe.OrderCancelledEventDecoder;
import com.oms.sbe.OrderFilledEventDecoder;
import com.oms.sbe.OrderPartiallyFilledEventDecoder;
import com.oms.sbe.OrderRejectedEventDecoder;
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

    // Pre-allocated decoders — never allocate inside doWork()
    private final MessageHeaderDecoder             headerDecoder    = new MessageHeaderDecoder();
    private final OrderAcceptedEventDecoder        acceptedDecoder  = new OrderAcceptedEventDecoder();
    private final OrderFilledEventDecoder          filledDecoder    = new OrderFilledEventDecoder();
    private final OrderRejectedEventDecoder        rejectedDecoder  = new OrderRejectedEventDecoder();
    private final OrderCancelledEventDecoder       cancelledDecoder = new OrderCancelledEventDecoder();
    private final OrderAmendedEventDecoder         amendedDecoder   = new OrderAmendedEventDecoder();
    private final OrderPartiallyFilledEventDecoder partialDecoder   = new OrderPartiallyFilledEventDecoder();
    private final CancelRejectedEventDecoder       cancelRejDecoder = new CancelRejectedEventDecoder();

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
            case OrderAcceptedEventDecoder.TEMPLATE_ID          -> handleOrderAccepted(buffer, offset);
            case OrderFilledEventDecoder.TEMPLATE_ID            -> handleOrderFilled(buffer, offset);
            case OrderRejectedEventDecoder.TEMPLATE_ID          -> handleOrderRejected(buffer, offset);
            case OrderCancelledEventDecoder.TEMPLATE_ID         -> handleOrderCancelled(buffer, offset);
            case OrderAmendedEventDecoder.TEMPLATE_ID           -> handleOrderAmended(buffer, offset);
            case OrderPartiallyFilledEventDecoder.TEMPLATE_ID   -> handleOrderPartiallyFilled(buffer, offset);
            case CancelRejectedEventDecoder.TEMPLATE_ID         -> handleCancelRejected(buffer, offset);
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

    private void handleOrderRejected(DirectBuffer buffer, int offset) {
        rejectedDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
        // Order never reached OPEN state — no view entry to update
        log.info()
            .append("[view-server] OrderRejectedEvent orderId=").append(rejectedDecoder.orderId())
            .append(" reason=").append(rejectedDecoder.rejectReason().name())
            .commit();
    }

    private void handleOrderCancelled(DirectBuffer buffer, int offset) {
        cancelledDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        final long orderId = cancelledDecoder.orderId();
        final OrderView existing = orders.get(orderId);
        if (existing == null) {
            log.warn()
                .append("[view-server] OrderCancelledEvent for unknown orderId=").append(orderId)
                .commit();
            return;
        }

        final OrderView updated = existing.withCancel();
        orders.put(orderId, updated);
        log.info().append("[view-server] ").append(updated.toString()).commit();
    }

    private void handleOrderAmended(DirectBuffer buffer, int offset) {
        amendedDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        final long orderId = amendedDecoder.orderId();
        final OrderView existing = orders.get(orderId);
        if (existing == null) {
            log.warn()
                .append("[view-server] OrderAmendedEvent for unknown orderId=").append(orderId)
                .commit();
            return;
        }

        final OrderView updated = existing.withAmend(amendedDecoder.newPrice(), amendedDecoder.newQuantity());
        orders.put(orderId, updated);
        log.info().append("[view-server] ").append(updated.toString()).commit();
    }

    private void handleOrderPartiallyFilled(DirectBuffer buffer, int offset) {
        partialDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        final long orderId = partialDecoder.orderId();
        final OrderView existing = orders.get(orderId);
        if (existing == null) {
            log.warn()
                .append("[view-server] OrderPartiallyFilledEvent for unknown orderId=").append(orderId)
                .commit();
            return;
        }

        final OrderView updated = existing.withPartialFill(
                partialDecoder.fillPrice(), partialDecoder.fillQuantity(), partialDecoder.remainingQty());
        orders.put(orderId, updated);
        log.info().append("[view-server] ").append(updated.toString()).commit();
    }

    private void handleCancelRejected(DirectBuffer buffer, int offset) {
        cancelRejDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
        // Order view unchanged — just log the rejection
        log.info()
            .append("[view-server] CancelRejectedEvent orderId=").append(cancelRejDecoder.orderId())
            .append(" reason=").append(cancelRejDecoder.rejectReason().name())
            .append(" (order unchanged)")
            .commit();
    }
}
