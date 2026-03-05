package com.oms.fix.acceptor;

import com.oms.common.OmsStreams;
import com.oms.fix.aggregate.FixOrderAggregateAgent;
import com.oms.fix.sbe.MessageHeaderDecoder;
import com.oms.fix.sbe.PlaceOrderCommandDecoder;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.YieldingIdleStrategy;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.engine.LowResourceEngineScheduler;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;

import java.util.Collections;

/**
 * M2: Artio FIX acceptor — listens on TCP port 9880, logs Logon/Logout.
 *
 * <p>Requires {@link com.oms.driver.OmsMediaDriverMain} to be running first.
 * All processes share the Aeron dir at {@value #AERON_DIR} and the archive on port 8010.
 *
 * <p>Bootstrap order (required by Artio):
 * <ol>
 *   <li>{@code OmsMediaDriverMain} — shared ArchivingMediaDriver (external process)
 *   <li>Configure {@link EngineConfiguration#aeronArchiveContext()} to point at port 8010.
 *   <li>Call {@link FixEngine#launch(EngineConfiguration)}, which connects to the running driver.
 *   <li>Connect a {@link FixLibrary} on the same IPC channel.
 * </ol>
 *
 * <p>Run before FixClientMain. Press Ctrl-C for a clean Logout.
 */
public class FixAcceptorMain
{
    // Artio engine↔library channel — IPC since both run in the same JVM process.
    static final String LIBRARY_CHANNEL = "aeron:ipc";
    static final int FIX_PORT = 9880;
    private static final String LOG_DIR = "./fix-acceptor-logs";

    // Shared Aeron dir — must match OmsMediaDriverMain.AERON_DIR
    private static final String AERON_DIR = "/tmp/aeron-oms";

    // All processes share the single archive on port 8010 (run by OmsMediaDriverMain).
    // Artio's internal archive client needs a fixed response port — ephemeral :0 can hang.
    // 8031 is dedicated to this process's archive responses; no collision with OmsApp (:0)
    // or the FIX client (8021).
    private static final String ARCHIVE_CONTROL_CHANNEL  = "aeron:udp?endpoint=localhost:8010";
    private static final String ARCHIVE_RESPONSE_CHANNEL = "aeron:udp?endpoint=localhost:8031";

    public static void main(final String[] args) throws Exception
    {
        // Force Artio's internal Aeron.connect() calls to use the shared driver dir.
        System.setProperty("aeron.dir", AERON_DIR);

        // Step 1: Connect Aeron to the shared driver (OmsMediaDriverMain must be running).
        final Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(AERON_DIR));

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
        final Subscription         verifyPlaceOrderSub = aeron.addSubscription(OmsStreams.IPC, OmsStreams.COMMAND_STREAM);
        final MessageHeaderDecoder verifyHeaderDecoder = new MessageHeaderDecoder();
        final PlaceOrderCommandDecoder verifyPlaceDecoder = new PlaceOrderCommandDecoder();

        // Step 2: Configure FixEngine — point aeronArchiveContext at the shared archive (port 8010).
        final EngineConfiguration engineCfg = new EngineConfiguration()
            .bindTo("0.0.0.0", FIX_PORT)
            .libraryAeronChannel(LIBRARY_CHANNEL)
            .logFileDir(LOG_DIR)
            .deleteLogFileDirOnStart(true)
            // LowResourceEngineScheduler collapses framer/sender/receiver threads — POC only.
            .scheduler(new LowResourceEngineScheduler());

        engineCfg.aeronArchiveContext()
            .controlRequestChannel(ARCHIVE_CONTROL_CHANNEL)   // shared archive UDP 8010
            .controlResponseChannel(ARCHIVE_RESPONSE_CHANNEL) // fixed port 8031 — avoids ephemeral hang
            .aeronDirectoryName(AERON_DIR);

        // Step 3: Launch engine (connects to the running shared driver).
        final FixEngine engine = FixEngine.launch(engineCfg);

        // Step 4: Connect FixLibrary (connects to engine via IPC).
        final FixSessionAcquireHandler acquireHandler = new FixSessionAcquireHandler(publisher);

        final LibraryConfiguration libraryCfg = new LibraryConfiguration()
            .sessionAcquireHandler(acquireHandler)
            .sessionExistsHandler(
                (lib, surrogateId, localCompId, localSubId, localLocationId,
                 remoteCompId, remoteSubId, remoteLocationId, logonSeqNum, seqIndex) ->
                {
                    System.out.printf("[Acceptor] Existing session: remote=%s — requesting acquisition%n", remoteCompId);
                    lib.requestSession(surrogateId, -1, seqIndex, 5_000L);
                })
            .libraryAeronChannels(Collections.singletonList(LIBRARY_CHANNEL));

        final FixLibrary library = FixLibrary.connect(libraryCfg);

        // Shutdown: close in reverse order — library → agents → Aeron → engine.
        // Driver is managed by OmsMediaDriverMain — do NOT close it here.
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            System.out.println("[Acceptor] Shutting down — Artio will send Logout to all sessions.");
            library.close();
            fixAggRunner.close();
            sequencerRunner.close();
            verifyPlaceOrderSub.close();
            fixAggCommandSub.close();
            internalCommandPub.close();
            commandIngressSub.close();
            sequencedCommandPub.close();
            commandPub.close();
            aeron.close();
            engine.close();
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
