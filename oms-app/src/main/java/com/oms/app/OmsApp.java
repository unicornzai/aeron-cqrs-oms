package com.oms.app;

import com.oms.aggregate.OrderAggregateAgent;
import com.oms.api.OrderQueryServer;
import com.oms.common.OmsStreams;
import com.oms.handlers.FillSimulatorHandler;
import com.oms.ingress.OrderIngressAgent;
import com.oms.readmodel.db.DatabaseReadModelStub;
import com.oms.readmodel.view.ViewServerReadModel;
import com.oms.sequencer.SequencerAgent;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import org.agrona.CloseHelper;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.YieldingIdleStrategy;

import java.util.List;

/**
 * OMS POC bootstrap — single-process entry point.
 *
 * <p>Connects to the shared {@link com.oms.driver.OmsMediaDriverMain} at {@code /tmp/aeron-oms}
 * (must be running before this process starts), wires all Aeron publications and subscriptions,
 * instantiates each component as an {@link org.agrona.concurrent.Agent}, and runs each on its
 * own dedicated {@link AgentRunner} thread.
 *
 * TODO(POC): use dedicated Aeron for archive control to isolate back-pressure
 * TODO(POC): add secondary Archive node for HA
 */
public class OmsApp {

    // Shared Aeron dir — must match OmsMediaDriverMain.AERON_DIR
    private static final String AERON_DIR = "/tmp/aeron-oms";
    // Archive control on OmsMediaDriverMain's fixed port
    private static final String ARCHIVE_CONTROL_CHANNEL = "aeron:udp?endpoint=localhost:8010";

    public static void main(String[] args) throws InterruptedException {

        // ── 1. Connect to shared MediaDriver ─────────────────────────────────
        // OmsMediaDriverMain must be running before this process starts.
        final Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(AERON_DIR));

        // ── 2. Connect AeronArchive client ────────────────────────────────────
        // Connects to the Archive running inside OmsMediaDriverMain on port 8010.
        // Ephemeral response port (:0) is safe here — OmsApp is not Artio and does
        // not have the fixed-port requirement that Artio's archive client has.
        final AeronArchive archive = AeronArchive.connect(
                new AeronArchive.Context()
                        .aeron(aeron)
                        .controlRequestChannel(ARCHIVE_CONTROL_CHANNEL)
                        .controlResponseChannel("aeron:udp?endpoint=localhost:0"));

        // ── 3. Start recordings BEFORE any publications ───────────────────────
        // startRecording() is idempotent — safe on every restart.
        // Archive tracks by channel+streamId; re-calling creates a new recording segment
        // for each boot (previous boot's recording is preserved for replay).
        // TODO(POC): validate return subscriptionId > 0
        archive.startRecording(OmsStreams.IPC, OmsStreams.COMMAND_STREAM, SourceLocation.LOCAL);
        archive.startRecording(OmsStreams.IPC, OmsStreams.EVENT_STREAM,   SourceLocation.LOCAL);

        // ── 4. Sequencer channels ─────────────────────────────────────────────
        final Subscription commandIngressSub = aeron.addSubscription(
                OmsStreams.IPC, OmsStreams.COMMAND_INGRESS_STREAM);
        final Subscription eventIngressSub   = aeron.addSubscription(
                OmsStreams.IPC, OmsStreams.EVENT_INGRESS_STREAM);
        final Publication commandStreamPub   = aeron.addPublication(
                OmsStreams.IPC, OmsStreams.COMMAND_STREAM);
        final Publication eventStreamPub     = aeron.addPublication(
                OmsStreams.IPC, OmsStreams.EVENT_STREAM);

        // ── 5. Component channels ─────────────────────────────────────────────
        final Publication commandIngressPub  = aeron.addPublication(
                OmsStreams.IPC, OmsStreams.COMMAND_INGRESS_STREAM);

        // OrderAggregateAgent and FillSimulatorHandler each get their own Publication on
        // Event Ingress (StreamId 11). Aeron IPC allows multiple concurrent publishers.
        final Publication eventIngressPub1   = aeron.addPublication(
                OmsStreams.IPC, OmsStreams.EVENT_INGRESS_STREAM);  // used by OrderAggregateAgent
        final Publication eventIngressPub2   = aeron.addPublication(
                OmsStreams.IPC, OmsStreams.EVENT_INGRESS_STREAM);  // used by FillSimulatorHandler

        // Each downstream subscriber gets its own independent subscription position.
        final Subscription commandStreamSub         = aeron.addSubscription(
                OmsStreams.IPC, OmsStreams.COMMAND_STREAM);
        final Subscription eventStreamSubAggregate  = aeron.addSubscription(
                OmsStreams.IPC, OmsStreams.EVENT_STREAM);   // OrderAggregateAgent (observes fills)
        final Subscription eventStreamSubFill        = aeron.addSubscription(
                OmsStreams.IPC, OmsStreams.EVENT_STREAM);   // FillSimulatorHandler
        final Subscription eventStreamSubDb          = aeron.addSubscription(
                OmsStreams.IPC, OmsStreams.EVENT_STREAM);   // DatabaseReadModelStub
        final Subscription eventStreamSubView        = aeron.addSubscription(
                OmsStreams.IPC, OmsStreams.EVENT_STREAM);   // ViewServerReadModel

        // ── 6. Agents ─────────────────────────────────────────────────────────
        final SequencerAgent        sequencer  = new SequencerAgent(
                commandIngressSub, eventIngressSub, commandStreamPub, eventStreamPub);
        final OrderIngressAgent     ingress    = new OrderIngressAgent(commandIngressPub);
        // Aggregate replays the Event Stream on startup to rebuild in-memory order state.
        // TODO(POC): size term buffer based on max replay duration × msg rate
        final OrderAggregateAgent   aggregate  = new OrderAggregateAgent(
                commandStreamSub, eventIngressPub1, eventStreamSubAggregate, aeron, archive);
        final FillSimulatorHandler  fillSim    = new FillSimulatorHandler(eventStreamSubFill, eventIngressPub2);
        final DatabaseReadModelStub dbModel    = new DatabaseReadModelStub(eventStreamSubDb);
        final ViewServerReadModel   viewModel  = new ViewServerReadModel(
                eventStreamSubView, aeron, archive);

        // ── M5: Query server (port 8081) ──────────────────────────────────────
        // Listener registered BEFORE startOnThread() — no updates can be missed.
        final OrderQueryServer queryServer = new OrderQueryServer();
        viewModel.setListener(queryServer);
        queryServer.start(viewModel);

        // ── 7. AgentRunner threads ────────────────────────────────────────────
        // YieldingIdleStrategy: backs off with Thread.yield() when idle. Good balance of
        // latency vs CPU for a POC. Swap to BusySpinIdleStrategy for minimal latency in prod.
        final List<AgentRunner> runners = List.of(
                new AgentRunner(new YieldingIdleStrategy(), Throwable::printStackTrace, null, sequencer),
                new AgentRunner(new YieldingIdleStrategy(), Throwable::printStackTrace, null, ingress),
                new AgentRunner(new YieldingIdleStrategy(), Throwable::printStackTrace, null, aggregate),
                new AgentRunner(new YieldingIdleStrategy(), Throwable::printStackTrace, null, fillSim),
                new AgentRunner(new YieldingIdleStrategy(), Throwable::printStackTrace, null, dbModel),
                new AgentRunner(new YieldingIdleStrategy(), Throwable::printStackTrace, null, viewModel)
        );

        runners.forEach(AgentRunner::startOnThread);

        // ── 8. Shutdown hook ──────────────────────────────────────────────────
        // Order matters: close agents first (onClose() writes ViewServer checkpoint),
        // then archive, then aeron. Driver is managed by OmsMediaDriverMain.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            runners.forEach(r -> CloseHelper.quietClose(r));  // onClose() writes checkpoint
            queryServer.stop();   // M5: stop Undertow after agent threads are down
            CloseHelper.quietClose(archive);
            CloseHelper.quietClose(aeron);
        }, "oms-shutdown"));

        // ── 9. Block main thread ──────────────────────────────────────────────
        Thread.currentThread().join();
    }
}
