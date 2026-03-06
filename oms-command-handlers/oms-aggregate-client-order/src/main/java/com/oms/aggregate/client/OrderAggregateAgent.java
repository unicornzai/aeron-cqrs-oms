package com.oms.aggregate.client;

import com.epam.deltix.gflog.api.Log;
import com.epam.deltix.gflog.api.LogFactory;
import com.oms.fix.sbe.PlaceOrderCommandDecoder;
import com.oms.sbe.*;
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
public class OrderAggregateAgent implements OrderCommandApi, OrderEventApi, Agent {

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
    private final OrderRejectedEventDecoder rejectedDecoder   = new OrderRejectedEventDecoder();
    private final OrderCancelledEventEncoder cancelledEncoder  = new OrderCancelledEventEncoder();
    private final OrderCancelledEventDecoder cancelledDecoder  = new OrderCancelledEventDecoder();
    private final OrderAmendedEventEncoder   amendedEncoder    = new OrderAmendedEventEncoder();
    private final OrderAmendedEventDecoder   amendedDecoder    = new OrderAmendedEventDecoder();
    private final CancelRejectedEventEncoder cancelRejEncoder  = new CancelRejectedEventEncoder();
    private final CancelRejectedEventDecoder cancelRejDecoder  = new CancelRejectedEventDecoder();

    // Pre-allocated replay decoders — reused across all replayed messages
    private final OrderAcceptedEventDecoder  replayAcceptedDecoder  = new OrderAcceptedEventDecoder();
    private final OrderCancelledEventDecoder replayCancelledDecoder = new OrderCancelledEventDecoder();


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
                applyOrderAcceptedEvent(replayAcceptedDecoder);
            }
            case OrderRejectedEventDecoder.TEMPLATE_ID -> {
                rejectedDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                applyOrderRejectedEvent(rejectedDecoder);
            }
            case CancelRejectedEventDecoder.TEMPLATE_ID -> {
                cancelRejDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                applyCancelRejectedEvent(cancelRejDecoder);
            }
            case OrderCancelledEventDecoder.TEMPLATE_ID -> {
                replayCancelledDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                applyOrderCancelledEvent(replayCancelledDecoder);
            }
            case OrderAmendedEventDecoder.TEMPLATE_ID -> {
                amendedDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                applyOrderAmmendedEvent(amendedDecoder);
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
        }
    }

    // ── Command stream handler ────────────────────────────────────────────────

    private void onCommandFragment(DirectBuffer buffer, int offset, int length, Header header) {
        headerDecoder.wrap(buffer, offset);
        final int templateId = headerDecoder.templateId();

        if (templateId == PlaceOrderCommandDecoder.TEMPLATE_ID) {
            handlePlaceOrder(buffer, offset);
        } else if (templateId == NewOrderCommandDecoder.TEMPLATE_ID) {
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
        handleNewOrder(cmdDecoder);
    }

    @Override
    public void handleNewOrder(NewOrderCommandDecoder cmdDecoder) {
        final long orderId       = cmdDecoder.orderId();
        final long accountId     = cmdDecoder.accountId();
        final long price         = cmdDecoder.price();
        final long quantity      = cmdDecoder.quantity();
        final long correlationId = cmdDecoder.correlationId();

        log.info()
                .append("[order-aggregate] new order command orderId=").append(orderId)
                .append(" symbol=").append(cmdDecoder.instrument())
                .append(" qty=").append(quantity)
                .append(" price=").append(price)
                .commit();

        // Validation — reject path
        RejectReason rejectReason = null;
        if (price <= 0) {
            rejectReason = RejectReason.INVALID_PRICE;
        }
        else if (quantity <= 0) {
            rejectReason = RejectReason.INVALID_QUANTITY;
        }
        else if (orders.containsKey(orderId)) {
            rejectReason = RejectReason.DUPLICATE_ORDER;
        }

        if (rejectReason != null) {
            rejectedEncoder.wrapAndApplyHeader(encodingBuffer, 0, headerEncoder)
                    .sequenceNumber(0)
                    .timestamp(System.nanoTime())
                    .correlationId(0L)
                    .orderId(orderId)
                    .accountId(0L)
                    .rejectReason(rejectReason);

            publishEvent(rejectedEncoder);
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

        publishEvent(acceptedEncoder);
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
        handlePlaceOrder(placeOrderCmdDecoder);
    }

    @Override
    public void handlePlaceOrder(PlaceOrderCommandDecoder cmdDecoder) {
        final long   orderId    = cmdDecoder.orderId();
        final String rawSymbol  = cmdDecoder.symbol();
        // SBE char arrays may be NUL/space padded — strip trailing garbage
        int end = rawSymbol.length();
        while (end > 0 && (rawSymbol.charAt(end - 1) == '\0' || rawSymbol.charAt(end - 1) == ' ')) end--;
        final String symbol = rawSymbol.substring(0, end);

        final com.oms.fix.sbe.SideEnum    fixSide    = cmdDecoder.side();
        final com.oms.fix.sbe.OrdTypeEnum fixOrdType = cmdDecoder.ordType();

        final long priceFixed8 = decimal64ToFixed8(
                cmdDecoder.price().mantissa(), cmdDecoder.price().exponent());
        final long qtyFixed8   = decimal64ToFixed8(
                cmdDecoder.orderQty().mantissa(), cmdDecoder.orderQty().exponent());

        log.info()
                .append("[order-aggregate] place order command orderId=").append(orderId)
                .append(" symbol=").append(rawSymbol)
                .append(" qty=").append(qtyFixed8)
                .append(" price=").append(priceFixed8)
                .commit();

        // Validation
        RejectReason rejectReason = null;
        if (symbol.isEmpty()) {
            rejectReason = RejectReason.UNKNOWN_INSTRUMENT;
        }
        else if (qtyFixed8 <= 0) {
            rejectReason = RejectReason.INVALID_QUANTITY;
        }
        else if (fixOrdType == com.oms.fix.sbe.OrdTypeEnum.LIMIT && priceFixed8 <= 0) {
            rejectReason = RejectReason.INVALID_PRICE;
        }
        else if (orders.containsKey(orderId)) {
            rejectReason = RejectReason.DUPLICATE_ORDER;
        }

        if (rejectReason != null) {
            rejectedEncoder.wrapAndApplyHeader(encodingBuffer, 0, headerEncoder)
                    .sequenceNumber(0)
                    .timestamp(System.nanoTime())
                    .correlationId(0L)
                    .orderId(orderId)
                    .accountId(0L)
                    .rejectReason(rejectReason);

            publishEvent(rejectedEncoder);
            return;
        }

        // Map fix-sbe enums → oms-sbe enums (both use 0=BUY/LIMIT, 1=SELL/MARKET)
        final Side      side      = fixSide    == com.oms.fix.sbe.SideEnum.BUY      ? Side.BUY        : Side.SELL;
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

        publishEvent(acceptedEncoder);
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
            cancelRejEncoder.wrapAndApplyHeader(encodingBuffer, 0, headerEncoder)
                    .sequenceNumber(0)
                    .timestamp(System.nanoTime())
                    .correlationId(correlationId)
                    .orderId(orderId)
                    .rejectReason(RejectReason.RISK_BREACH);

            publishEvent(cancelRejEncoder);
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

        publishEvent(cancelledEncoder);
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

        publishEvent(amendedEncoder);
    }

    // ── Event publishers ──────────────────────────────────────────────────────

    private void publishEvent(OrderAcceptedEventEncoder eventEncoder) {
        final int msgLen = MessageHeaderEncoder.ENCODED_LENGTH + OrderAcceptedEventEncoder.BLOCK_LENGTH;
        final long result = eventIngressPub.offer(eventEncoder.buffer(), 0, msgLen);
        if (result > 0) {
            replayAcceptedDecoder.wrapAndApplyHeader(eventEncoder.buffer(), 0, headerDecoder);
            applyOrderAcceptedEvent(replayAcceptedDecoder);
        } else {
            // TODO(POC): add back-pressure retry budget
            log.warn()
                    .append("[aggregate] failed to publish OrderAcceptedEvent orderId=").append(replayAcceptedDecoder.orderId())
                    .append(" result=").append(result)
                    .commit();
        }
    }

    private void publishEvent(OrderRejectedEventEncoder eventEncoder) {
        final int msgLen = MessageHeaderEncoder.ENCODED_LENGTH + OrderRejectedEventEncoder.BLOCK_LENGTH;
        final long result = eventIngressPub.offer(encodingBuffer, 0, msgLen);
        if (result > 0) {
            rejectedDecoder.wrapAndApplyHeader(eventEncoder.buffer(), 0, headerDecoder);
            applyOrderRejectedEvent(rejectedDecoder);
        } else {
            // TODO(POC): add back-pressure retry budget
            log.warn()
                    .append("[aggregate] failed to publish OrderRejectedEvent orderId=").append(rejectedDecoder.orderId())
                    .commit();
        }
    }

    private void publishEvent(CancelRejectedEventEncoder eventEncoder) {
        final int msgLen = MessageHeaderEncoder.ENCODED_LENGTH + CancelRejectedEventEncoder.BLOCK_LENGTH;
        final long result = eventIngressPub.offer(encodingBuffer, 0, msgLen);
        if (result > 0) {
            cancelRejDecoder.wrapAndApplyHeader(eventEncoder.buffer(), 0, headerDecoder);
            applyCancelRejectedEvent(cancelRejDecoder);
        } else {
            log.warn()
                    .append("[aggregate] failed to publish CancelRejectedEvent orderId=").append(cancelRejDecoder.orderId())
                    .commit();
        }
    }

    private void publishEvent(OrderCancelledEventEncoder eventEncoder) {
        final int msgLen = MessageHeaderEncoder.ENCODED_LENGTH + OrderCancelledEventEncoder.BLOCK_LENGTH;
        final long result = eventIngressPub.offer(encodingBuffer, 0, msgLen);
        if (result > 0) {
            cancelledDecoder.wrapAndApplyHeader(eventEncoder.buffer(), 0, headerDecoder);
            applyOrderCancelledEvent(cancelledDecoder);
        } else {
            // TODO(POC): add back-pressure retry budget
            log.warn()
                    .append("[aggregate] failed to publish OrderCancelledEvent orderId=").append(cancelledDecoder.orderId())
                    .append(" result=").append(result)
                    .commit();
        }
    }

    private void publishEvent(OrderAmendedEventEncoder eventEncoder) {
        final int msgLen = MessageHeaderEncoder.ENCODED_LENGTH + OrderAmendedEventEncoder.BLOCK_LENGTH;
        final long result = eventIngressPub.offer(encodingBuffer, 0, msgLen);
        if (result > 0) {
            amendedDecoder.wrapAndApplyHeader(eventEncoder.buffer(), 0, headerDecoder);
        } else {
            // TODO(POC): add back-pressure retry budget
            log.warn()
                    .append("[aggregate] failed to publish OrderAmendedEvent orderId=").append(amendedDecoder.orderId())
                    .append(" result=").append(result)
                    .commit();
        }
    }

    // ── Event handlers ──────────────────────────────────────────────────────

    @Override
    public void applyOrderAcceptedEvent(OrderAcceptedEventDecoder eventDecoder) {
        final long orderId = eventDecoder.orderId();
        orders.put(orderId, new OrderState(
                orderId,
                eventDecoder.accountId(),
                eventDecoder.instrument(),
                eventDecoder.side(),
                eventDecoder.orderType(),
                eventDecoder.timeInForce(),
                eventDecoder.price(),
                eventDecoder.quantity(),
                OrderStatus.OPEN));

        log.info()
                .append("[order-aggregate] order accepted orderId=").append(orderId)
                .append(" symbol=").append(eventDecoder.instrument())
                .append(" qty=").append(eventDecoder.quantity())
                .append(" price=").append(eventDecoder.price())
                .commit();
    }

    @Override
    public void applyOrderRejectedEvent(OrderRejectedEventDecoder eventDecoder) {
        log.info()
                .append("[order-aggregate] rejected orderId=").append(eventDecoder.orderId())
                .append(" reason=").append(eventDecoder.rejectReason().name())
                .commit();
    }

    @Override
    public void applyCancelRejectedEvent(CancelRejectedEventDecoder eventDecoder) {
        log.info()
                .append("[aggregate] cancel rejected orderId=").append(cancelRejDecoder.orderId())
                .append(" reason=").append(cancelRejDecoder.rejectReason().name())
                .commit();
    }

    @Override
    public void applyOrderCancelledEvent(OrderCancelledEventDecoder eventDecoder) {
        final OrderState state = orders.get(eventDecoder.orderId());
        state.status = OrderStatus.CANCELLED;
        log.info()
                .append("[aggregate] cancelled orderId=").append(eventDecoder.orderId())
                .append(" → CANCELLED")
                .commit();
    }

    @Override
    public void applyOrderAmmendedEvent(OrderAmendedEventDecoder eventDecoder) {
        final OrderState state = orders.get(amendedDecoder.orderId());
        state.currentPrice    = amendedDecoder.newPrice();
        state.currentQuantity = amendedDecoder.newQuantity();
        log.info()
                .append("[aggregate] amended orderId=").append(amendedDecoder.orderId())
                .append(" newPrice=").append(amendedDecoder.newPrice())
                .append(" newQty=").append(amendedDecoder.newQuantity())
                .commit();
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
}
