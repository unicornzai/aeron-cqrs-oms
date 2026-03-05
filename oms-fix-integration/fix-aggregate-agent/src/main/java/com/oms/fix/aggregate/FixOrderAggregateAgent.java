package com.oms.fix.aggregate;

import com.oms.fix.sbe.MessageHeaderDecoder;
import com.oms.fix.sbe.NewOrderSingleCommandDecoder;
import com.oms.fix.sbe.PlaceOrderCommandDecoder;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.collections.Object2LongHashMap;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M5: Anti-corruption layer between the FIX acceptor and the core OMS domain.
 *
 * <p>Subscribes to the <em>sequenced</em> Command Stream (IPC stream 1), consumes
 * {@code NewOrderSingleCommand} (templateId=10), performs {@code clOrdId→orderId}
 * mapping, and publishes a {@code PlaceOrderCommand} (templateId=20) back to the
 * Command Ingress Stream (IPC stream 10) so that {@link com.oms.fix.acceptor.FixSequencerAgent}
 * stamps it and re-publishes it to stream 1 — completing the FIX→domain translation.
 *
 * <p>{@code PlaceOrderCommand} messages arriving on stream 1 (after re-sequencing)
 * are silently skipped — they are our own outbound messages returning.
 *
 * <p>Single-threaded via {@link org.agrona.concurrent.AgentRunner}; all maps and
 * encoders need no synchronisation.
 */
public final class FixOrderAggregateAgent implements Agent
{
    // Enough for header (8) + PlaceOrderCommand block (56) with headroom.
    private static final int ENCODING_BUFFER_SIZE = 256;

    private final Subscription commandStreamSub;  // IPC stream 1 (sequenced)
    private final Publication  internalCommandPub; // IPC stream 10 (back to sequencer)

    // clOrdId → internal orderId; missingValue=-1 means "not seen yet"
    private final Object2LongHashMap<String> orderIdByClOrdId =
        new Object2LongHashMap<>(-1L);

    // orderId → FIX-layer state; shared with FixExecReportBridge for ExecReport correlation.
    // ConcurrentHashMap: agent thread writes, bridge reads on the Artio poll thread.
    private final ConcurrentHashMap<Long, FixOrderState> fixStateByOrderId;

    private final FixCommandTranslator translator = new FixCommandTranslator();

    // Pre-allocated decoders — reused per fragment, zero allocation on hot path.
    private final MessageHeaderDecoder          headerDecoder = new MessageHeaderDecoder();
    private final NewOrderSingleCommandDecoder  nosDecoder    = new NewOrderSingleCommandDecoder();

    // Pre-allocated output buffer for translated PlaceOrderCommand.
    private final UnsafeBuffer encodingBuffer =
        new UnsafeBuffer(ByteBuffer.allocateDirect(ENCODING_BUFFER_SIZE));

    public FixOrderAggregateAgent(final Subscription commandStreamSub,
                                  final Publication  internalCommandPub)
    {
        this(commandStreamSub, internalCommandPub, new ConcurrentHashMap<>());
    }

    public FixOrderAggregateAgent(final Subscription commandStreamSub,
                                  final Publication  internalCommandPub,
                                  final ConcurrentHashMap<Long, FixOrderState> sharedFixStateMap)
    {
        this.commandStreamSub   = commandStreamSub;
        this.internalCommandPub = internalCommandPub;
        this.fixStateByOrderId  = sharedFixStateMap;
    }

    @Override
    public int doWork()
    {
        return commandStreamSub.poll(this::onFragment, 10);
    }

    @Override
    public String roleName()
    {
        return "fix-order-aggregate";
    }

    @Override
    public void onStart()
    {
        System.out.println("[fix-order-aggregate] onStart");
    }

    @Override
    public void onClose()
    {
        System.out.println("[fix-order-aggregate] closed");
    }

    // ── Fragment dispatch ────────────────────────────────────────────────────

    private void onFragment(final DirectBuffer buffer, final int offset,
                            final int length, final Header header)
    {
        headerDecoder.wrap(buffer, offset);
        final int templateId = headerDecoder.templateId();

        if (templateId == NewOrderSingleCommandDecoder.TEMPLATE_ID)
        {
            nosDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
            onNewOrderSingleCommand(nosDecoder);
        }
        else if (templateId == PlaceOrderCommandDecoder.TEMPLATE_ID)
        {
            // Own PlaceOrderCommand returning after sequencing — skip silently.
        }
        // else: unknown template, ignore
    }

    // ── Command handlers ─────────────────────────────────────────────────────

    private void onNewOrderSingleCommand(final NewOrderSingleCommandDecoder nos)
    {
        final String clOrdId = nos.clOrdId(); // NUL-padded fixed array → trimmed String

        // Duplicate check — same clOrdId already processed.
        if (orderIdByClOrdId.containsKey(clOrdId))
        {
            System.out.printf("[FixAggAgent] WARN duplicate clOrdId=%s — ignoring%n", clOrdId);
            return;
        }

        // Allocate internal orderId and record both sides of the mapping.
        final long orderId = translator.nextOrderId();

        orderIdByClOrdId.put(clOrdId, orderId);
        fixStateByOrderId.put(orderId, new FixOrderState(clOrdId, nos.sessionId(), FixOrdStatus.PENDING_NEW));

        System.out.printf("[FixAggAgent] clOrdId=%s → orderId=%d %s%n",
            clOrdId, orderId, FixOrdStatus.PENDING_NEW);

        // Translate NOS → PlaceOrderCommand into pre-allocated encoding buffer.
        final int length = translator.translate(nos, encodingBuffer, orderId);

        // Publish to command ingress (stream 10) — sequencer will stamp and re-publish to stream 1.
        final long result = internalCommandPub.offer(encodingBuffer, 0, length);
        if (result < 0)
        {
            // TODO(POC): add bounded retry; in production back-pressure must be handled
            System.err.printf("[FixAggAgent] offer failed result=%d orderId=%d clOrdId=%s%n",
                result, orderId, clOrdId);
        }
    }
}
