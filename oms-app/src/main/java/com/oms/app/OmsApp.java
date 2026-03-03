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
import io.aeron.archive.Archive;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.driver.MediaDriver;
import org.agrona.CloseHelper;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.YieldingIdleStrategy;

import java.io.File;
import java.util.List;

/**
 * OMS POC bootstrap — single-process entry point.
 *
 * <p>Starts an embedded ArchivingMediaDriver (MediaDriver + Archive co-located),
 * wires all Aeron publications and subscriptions, instantiates each component as an
 * {@link org.agrona.concurrent.Agent}, and runs each on its own dedicated
 * {@link AgentRunner} thread.
 *
 * TODO(POC): switch to standalone MediaDriver+Archive processes for multi-process deployment.
 * TODO(POC): use dedicated Aeron for archive control to isolate back-pressure
 * TODO(POC): add secondary Archive node for HA
 */
public class OmsApp {

    public static void main(String[] args) throws InterruptedException {

        // ── 1. Embedded ArchivingMediaDriver ─────────────────────────────────
        // Wraps the MediaDriver with an Archive that records IPC streams to disk.
        // spiesSimulateConnection(true) is REQUIRED: Archive spy subscriptions must count
        // as real connected subscribers, otherwise IPC publications block at NOT_CONNECTED.
        // Archive dir is NOT deleted on start — persists across restarts for replay.
        // TODO(POC): externalise archive dir via system property oms.archive.dir
        // Archive control channel: UDP localhost so the embedded Archive and AeronArchive
        // client can exchange control messages. IPC would also work for same-process use,
        // but UDP is the idiomatic choice and avoids IPC stream-ID collisions.
        // TODO(POC): externalise port via system property oms.archive.control.port
        final String archiveControlChannel = "aeron:udp?endpoint=localhost:8010";

        final ArchivingMediaDriver archivingDriver = ArchivingMediaDriver.launch(
                new MediaDriver.Context()
                        .dirDeleteOnStart(true)
                        .spiesSimulateConnection(true),
                new Archive.Context()
                        .archiveDir(new File("archive"))
                        .controlChannel(archiveControlChannel)
                        // replicationChannel is required in 1.44.x — used for Archive-to-Archive
                        // replication (HA). For POC, point at an unused local endpoint.
                        // TODO(POC): wire real replication channel for secondary Archive node
                        .replicationChannel("aeron:udp?endpoint=localhost:8011")
                        .recordingEventsEnabled(false));  // TODO(POC): enable for production monitoring

        final Aeron aeron = Aeron.connect(new Aeron.Context()
                .aeronDirectoryName(archivingDriver.mediaDriver().aeronDirectoryName()));

        final AeronArchive archive = AeronArchive.connect(
                new AeronArchive.Context()
                        .aeron(aeron)
                        .controlRequestChannel(archiveControlChannel)
                        // ephemeral port — Archive sends responses back to this client-side channel
                        .controlResponseChannel("aeron:udp?endpoint=localhost:0"));

        // ── 2. Start recordings BEFORE any publications ───────────────────────
        // startRecording() is idempotent — safe on every restart.
        // Archive tracks by channel+streamId; re-calling creates a new recording segment
        // for each boot (previous boot's recording is preserved for replay).
        // TODO(POC): validate return subscriptionId > 0
        archive.startRecording(OmsStreams.IPC, OmsStreams.COMMAND_STREAM, SourceLocation.LOCAL);
        archive.startRecording(OmsStreams.IPC, OmsStreams.EVENT_STREAM,   SourceLocation.LOCAL);

        // ── 3. Sequencer channels ─────────────────────────────────────────────
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

        // ── 4. Component channels ─────────────────────────────────────────────
        // Ingress publishes commands to the pre-sequencer Command Ingress channel.
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

        // ── 5. Agents ─────────────────────────────────────────────────────────
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

        // ── 6. AgentRunner threads ────────────────────────────────────────────
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

        // ── 7. Shutdown hook ──────────────────────────────────────────────────
        // Order matters: close agents first (onClose() writes ViewServer checkpoint),
        // then archive, then aeron, then driver.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            runners.forEach(r -> CloseHelper.quietClose(r));  // onClose() writes checkpoint
            queryServer.stop();   // M5: stop Undertow after agent threads are down
            CloseHelper.quietClose(archive);
            CloseHelper.quietClose(aeron);
            CloseHelper.quietClose(archivingDriver);
        }, "oms-shutdown"));

        // ── 8. Block main thread ──────────────────────────────────────────────
        Thread.currentThread().join();
    }
}
