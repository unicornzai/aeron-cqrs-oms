package com.oms.app;

import com.oms.aggregate.OrderAggregateAgent;
import com.oms.common.OmsStreams;
import com.oms.handlers.FillSimulatorHandler;
import com.oms.ingress.OrderIngressAgent;
import com.oms.readmodel.db.DatabaseReadModelStub;
import com.oms.readmodel.view.ViewServerReadModel;
import com.oms.sequencer.SequencerAgent;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import org.agrona.CloseHelper;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;

import java.util.List;

/**
 * OMS POC bootstrap — single-process entry point.
 *
 * <p>Starts an embedded MediaDriver, wires all Aeron publications and subscriptions,
 * instantiates each component as an {@link org.agrona.concurrent.Agent}, and runs each
 * on its own dedicated {@link AgentRunner} thread.
 *
 * TODO(POC): switch to a standalone MediaDriver process for multi-process deployment.
 *            Replace embedded driver with Aeron.connect() pointing at /dev/shm/aeron.
 */
public class OmsApp {

    public static void main(String[] args) throws InterruptedException {

        // ── 1. Embedded MediaDriver ──────────────────────────────────────────
        // Single process for the POC — all IPC communication stays in shared memory.
        // TODO(POC): launch as a standalone process before moving to multi-process.
        final MediaDriver mediaDriver = MediaDriver.launchEmbedded();
        final Aeron aeron = Aeron.connect(
                new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));

        // ── 2. Sequencer channels ────────────────────────────────────────────
        // Sequencer subscribes to the pre-sequencer ingress streams...
        final Subscription commandIngressSub = aeron.addSubscription(
                OmsStreams.IPC, OmsStreams.COMMAND_INGRESS_STREAM);
        final Subscription eventIngressSub   = aeron.addSubscription(
                OmsStreams.IPC, OmsStreams.EVENT_INGRESS_STREAM);
        // ...and publishes to the canonical sequenced streams.
        final Publication commandStreamPub   = aeron.addPublication(
                OmsStreams.IPC, OmsStreams.COMMAND_STREAM);
        final Publication eventStreamPub     = aeron.addPublication(
                OmsStreams.IPC, OmsStreams.EVENT_STREAM);

        // ── 3. Component channels ────────────────────────────────────────────
        // Ingress publishes commands to the pre-sequencer Command Ingress channel.
        final Publication commandIngressPub  = aeron.addPublication(
                OmsStreams.IPC, OmsStreams.COMMAND_INGRESS_STREAM);

        // OrderAggregateAgent and FillSimulatorHandler each get their own Publication on
        // Event Ingress (StreamId 11). Aeron IPC allows multiple concurrent publishers —
        // the Sequencer receives all messages in arrival order.
        final Publication eventIngressPub1   = aeron.addPublication(
                OmsStreams.IPC, OmsStreams.EVENT_INGRESS_STREAM);  // used by OrderAggregateAgent
        final Publication eventIngressPub2   = aeron.addPublication(
                OmsStreams.IPC, OmsStreams.EVENT_INGRESS_STREAM);  // used by FillSimulatorHandler

        // Each downstream subscriber gets its own independent subscription position.
        // Aeron IPC supports multiple subscribers on the same stream — each sees all messages.
        final Subscription commandStreamSub      = aeron.addSubscription(
                OmsStreams.IPC, OmsStreams.COMMAND_STREAM);
        final Subscription eventStreamSubFill    = aeron.addSubscription(
                OmsStreams.IPC, OmsStreams.EVENT_STREAM);   // FillSimulatorHandler
        final Subscription eventStreamSubDb      = aeron.addSubscription(
                OmsStreams.IPC, OmsStreams.EVENT_STREAM);   // DatabaseReadModelStub (M4 replaces)
        final Subscription eventStreamSubView    = aeron.addSubscription(
                OmsStreams.IPC, OmsStreams.EVENT_STREAM);   // ViewServerReadModel

        // ── 4. Agents ────────────────────────────────────────────────────────
        final SequencerAgent        sequencer  = new SequencerAgent(
                commandIngressSub, eventIngressSub, commandStreamPub, eventStreamPub);
        final OrderIngressAgent     ingress    = new OrderIngressAgent(commandIngressPub);
        final OrderAggregateAgent   aggregate  = new OrderAggregateAgent(commandStreamSub, eventIngressPub1);
        final FillSimulatorHandler  fillSim    = new FillSimulatorHandler(eventStreamSubFill, eventIngressPub2);
        final DatabaseReadModelStub dbModel    = new DatabaseReadModelStub(eventStreamSubDb);
        final ViewServerReadModel   viewModel  = new ViewServerReadModel(eventStreamSubView);

        // ── 5. AgentRunner threads ───────────────────────────────────────────
        // YieldingIdleStrategy: backs off with Thread.yield() when idle. Good balance of
        // latency vs CPU for a POC. Swap to BusySpinIdleStrategy for minimal latency in prod.
        // YieldingIdleStrategy is stateless so a shared instance is safe, but use one per
        // runner to make future replacement (e.g. per-agent tuning) straightforward.
        final List<AgentRunner> runners = List.of(
                new AgentRunner(new YieldingIdleStrategy(), Throwable::printStackTrace, null, sequencer),
                new AgentRunner(new YieldingIdleStrategy(), Throwable::printStackTrace, null, ingress),
                new AgentRunner(new YieldingIdleStrategy(), Throwable::printStackTrace, null, aggregate),
                new AgentRunner(new YieldingIdleStrategy(), Throwable::printStackTrace, null, fillSim),
                new AgentRunner(new YieldingIdleStrategy(), Throwable::printStackTrace, null, dbModel),
                new AgentRunner(new YieldingIdleStrategy(), Throwable::printStackTrace, null, viewModel)
        );

        // startOnThread() creates a named daemon thread per runner.
        runners.forEach(AgentRunner::startOnThread);

        // ── 6. Shutdown hook ─────────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            runners.forEach(r -> CloseHelper.quietClose(r));
            CloseHelper.quietClose(aeron);
            CloseHelper.quietClose(mediaDriver);
        }, "oms-shutdown"));

        // ── 7. Block main thread ─────────────────────────────────────────────
        // Each agent runs on its own daemon thread; main must not exit.
        Thread.currentThread().join();
    }
}
