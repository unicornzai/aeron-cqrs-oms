package com.oms.fix.acceptor;

import com.oms.common.OmsStreams;
import com.oms.fix.aggregate.FixOrderAggregateAgent;
import com.oms.fix.sbe.MessageHeaderDecoder;
import com.oms.fix.sbe.PlaceOrderCommandDecoder;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.YieldingIdleStrategy;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.engine.LowResourceEngineScheduler;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;

import java.io.File;
import java.nio.file.Paths;
import java.util.Collections;

/**
 * M2: Artio FIX acceptor — listens on TCP port 9880, logs Logon/Logout.
 *
 * <p>Bootstrap order (required by Artio):
 * <ol>
 *   <li>Launch {@link ArchivingMediaDriver} — provides both the Aeron MediaDriver
 *       and the Aeron Archive that Artio uses for internal message replay.
 *   <li>Configure {@link EngineConfiguration#aeronArchiveContext()} to point at that archive.
 *   <li>Call {@link FixEngine#launch(EngineConfiguration)}, which connects to the running driver.
 *   <li>Connect a {@link FixLibrary} on the same IPC channel.
 * </ol>
 *
 * <p>Run before FixClientMain. Press Ctrl-C for a clean Logout.
 *
 * // TODO(POC): When integrating with OmsApp, share the single ArchivingMediaDriver
 * //            and coordinate archive channel ports (acceptor: 8030, OmsApp: 8010).
 * //            Two archives on the same port will conflict.
 */
public class FixAcceptorMain
{
    // Artio engine↔library channel — IPC since both run in the same JVM process.
    static final String LIBRARY_CHANNEL = "aeron:ipc";
    static final int FIX_PORT = 9880;
    private static final String LOG_DIR = "./fix-acceptor-logs";
    private static final String ARCHIVE_DIR = "./fix-acceptor-archive";
    // Dedicated Aeron dir — prevents acceptor dirDeleteOnStart from wiping the client's live dir.
    private static final String AERON_DIR = "./aeron-fix-acceptor";

    // Fixed UDP ports for archive control — avoids stream-ID collision between IPC archive
    // control (default stream 10) and commandPub (OmsStreams.COMMAND_INGRESS_STREAM = 10).
    // Response on a fixed port (8031) prevents the ephemeral-port hang seen with :0.
    // Does not conflict with OmsApp's archive (8010) or the FIX client's archive (8020).
    private static final String ARCHIVE_CONTROL_CHANNEL     = "aeron:udp?endpoint=localhost:8030";
    private static final String ARCHIVE_RESPONSE_CHANNEL    = "aeron:udp?endpoint=localhost:8031";
    private static final String ARCHIVE_REPLICATION_CHANNEL = "aeron:udp?endpoint=localhost:0";

    public static void main(final String[] args) throws Exception
    {
        // Resolve the Aeron dir to a canonical absolute path ONCE so that all components
        // (driver, archive, engine) use identical string paths and share the same CnC file.
        final String aeronDirAbsolute = Paths.get(AERON_DIR).toAbsolutePath().normalize().toString();
        // Force Artio's internal Aeron.connect() calls to use this same dir.
        System.setProperty("aeron.dir", aeronDirAbsolute);

        // Step 1: Launch ArchivingMediaDriver.
        // Artio's FixEngine calls Aeron.connect() — it does NOT start its own driver.
        // The archive is required for Artio's internal message replay (resend requests).
        // ThreadingMode.SHARED runs sender/receiver/conductor on a single thread — POC only.
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
            .aeronDirectoryName(aeronDirAbsolute)
            .dirDeleteOnStart(true)
            .threadingMode(ThreadingMode.SHARED);

        final Archive.Context archiveCtx = new Archive.Context()
            .archiveDir(new File(ARCHIVE_DIR))
            .deleteArchiveOnStart(true)
            .controlChannel(ARCHIVE_CONTROL_CHANNEL)
            .replicationChannel(ARCHIVE_REPLICATION_CHANNEL)
            .recordingEventsEnabled(false)
            .aeronDirectoryName(aeronDirAbsolute);  // match the driver dir explicitly

        final ArchivingMediaDriver archivingMediaDriver =
            ArchivingMediaDriver.launch(driverCtx, archiveCtx);

        // Step 2: Connect Aeron, open command-ingress publication, and wire the sequencer.
        // This Aeron.connect() shares the ArchivingMediaDriver started above.
        final Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDirAbsolute));

        // Acceptor publishes raw FIX commands here; Sequencer reads from this stream.
        final Publication commandPub = aeron.addPublication(OmsStreams.IPC, OmsStreams.COMMAND_INGRESS_STREAM);
        final CommandStreamPublisher publisher = new CommandStreamPublisher(commandPub);

        // M4: Sequencer — reads stream 10 (raw), stamps seq number, republishes to stream 1 (sequenced).
        // The Subscription must be created before the Publication is first offered to, otherwise
        // Aeron may not see a connected subscriber. Both live in this process's Aeron instance.
        final Subscription commandIngressSub = aeron.addSubscription(OmsStreams.IPC, OmsStreams.COMMAND_INGRESS_STREAM);
        final Publication  sequencedCommandPub = aeron.addPublication(OmsStreams.IPC, OmsStreams.COMMAND_STREAM);

        final FixSequencerAgent sequencerAgent = new FixSequencerAgent(commandIngressSub, sequencedCommandPub);
        // YieldingIdleStrategy: spins briefly then yields — good balance for a low-latency POC.
        final AgentRunner sequencerRunner = new AgentRunner(
            new YieldingIdleStrategy(), Throwable::printStackTrace, null, sequencerAgent);
        AgentRunner.startOnThread(sequencerRunner);

        // M5: FixOrderAggregateAgent — subscribes to sequenced commands (stream 1),
        // translates NewOrderSingleCommand (templateId=10) → PlaceOrderCommand (templateId=20),
        // and publishes back to stream 10 so the sequencer stamps it and re-delivers on stream 1.
        final Subscription fixAggCommandSub   = aeron.addSubscription(OmsStreams.IPC, OmsStreams.COMMAND_STREAM);
        final Publication  internalCommandPub = aeron.addPublication(OmsStreams.IPC, OmsStreams.COMMAND_INGRESS_STREAM);

        final FixOrderAggregateAgent fixAggAgent = new FixOrderAggregateAgent(fixAggCommandSub, internalCommandPub);
        final AgentRunner fixAggRunner = new AgentRunner(
            new YieldingIdleStrategy(), Throwable::printStackTrace, null, fixAggAgent);
        AgentRunner.startOnThread(fixAggRunner);

        // M5 verification: second subscriber on stream 1 to confirm PlaceOrderCommand appears.
        // Decoders are only used on the main while-loop thread so no synchronisation needed.
        final Subscription         verifyPlaceOrderSub = aeron.addSubscription(OmsStreams.IPC, OmsStreams.COMMAND_STREAM);
        final MessageHeaderDecoder verifyHeaderDecoder = new MessageHeaderDecoder();
        final PlaceOrderCommandDecoder verifyPlaceDecoder = new PlaceOrderCommandDecoder();

        // Step 3: Configure FixEngine — point aeronArchiveContext at the archive we just started.
        final EngineConfiguration engineCfg = new EngineConfiguration()
            .bindTo("0.0.0.0", FIX_PORT)
            .libraryAeronChannel(LIBRARY_CHANNEL)
            .logFileDir(LOG_DIR)
            .deleteLogFileDirOnStart(true)
            // LowResourceEngineScheduler collapses framer/sender/receiver threads — POC only.
            .scheduler(new LowResourceEngineScheduler());

        engineCfg.aeronArchiveContext()
            .controlRequestChannel(ARCHIVE_CONTROL_CHANNEL)   // UDP 8030
            .controlResponseChannel(ARCHIVE_RESPONSE_CHANNEL) // UDP 8031 — fixed port, not ephemeral
            .aeronDirectoryName(aeronDirAbsolute);

        // Step 4: Launch engine (connects to the running ArchivingMediaDriver).
        final FixEngine engine = FixEngine.launch(engineCfg);

        // Step 5: Connect FixLibrary (connects to engine via IPC).
        final FixSessionAcquireHandler acquireHandler = new FixSessionAcquireHandler(publisher);

        final LibraryConfiguration libraryCfg = new LibraryConfiguration()
            .sessionAcquireHandler(acquireHandler)
            .sessionExistsHandler(
                (lib, surrogateId, localCompId, localSubId, localLocationId,
                 remoteCompId, remoteSubId, remoteLocationId, logonSeqNum, seqIndex) ->
                {
                    System.out.printf("[Acceptor] Existing session: remote=%s — requesting acquisition%n", remoteCompId);
                    // Artio does NOT auto-dispatch existing sessions to sessionAcquireHandler;
                    // we must explicitly request ownership so onSessionAcquired fires.
                    lib.requestSession(surrogateId, -1, seqIndex, 5_000L);
                })
            .libraryAeronChannels(Collections.singletonList(LIBRARY_CHANNEL));

        final FixLibrary library = FixLibrary.connect(libraryCfg);

        // Shutdown: close in reverse order — library → agents → Aeron → engine → archivingMediaDriver.
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            System.out.println("[Acceptor] Shutting down — Artio will send Logout to all sessions.");
            library.close();
            fixAggRunner.close();
            sequencerRunner.close();       // stop sequencer thread before closing its pub/sub
            verifyPlaceOrderSub.close();
            fixAggCommandSub.close();
            internalCommandPub.close();
            commandIngressSub.close();
            sequencedCommandPub.close();
            commandPub.close();
            aeron.close();
            engine.close();
            archivingMediaDriver.close();
        }));

        System.out.println("[Acceptor] Listening on port " + FIX_PORT + ". Press Ctrl-C to stop.");

        while (!Thread.currentThread().isInterrupted())
        {
            library.poll(10);

            // M5 verification: log any PlaceOrderCommand that appears on stream 1.
            verifyPlaceOrderSub.poll((buf, off, len, hdr) ->
            {
                verifyHeaderDecoder.wrap(buf, off);
                if (verifyHeaderDecoder.templateId() == PlaceOrderCommandDecoder.TEMPLATE_ID)
                {
                    verifyPlaceDecoder.wrapAndApplyHeader(buf, off, verifyHeaderDecoder);
                    System.out.printf("[M5-Verify] PlaceOrderCommand orderId=%d symbol=%s side=%s seq=%d%n",
                        verifyPlaceDecoder.orderId(),
                        verifyPlaceDecoder.symbol(),
                        verifyPlaceDecoder.side(),
                        verifyPlaceDecoder.sequenceNumber());
                }
            }, 10);
        }
    }
}
