package com.oms.fix.client;

import io.aeron.Aeron;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.engine.LowResourceEngineScheduler;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.library.SessionConfiguration;
import uk.co.real_logic.artio.session.Session;

import java.io.File;
import java.nio.file.Paths;
import java.util.Collections;

/**
 * FIX initiator — connects to {@link FixAcceptorMain} on port 9880.
 *
 * <p>Uses its own embedded {@link ArchivingMediaDriver} for Artio's internal IPC and
 * FIX message replay. System.setProperty("aeron.dir") points Artio at this private dir.
 * The client process does not subscribe to OMS IPC streams — it is purely a FIX transport.
 *
 * <p>Run after OmsMediaDriverMain, OmsApp, and FixAcceptorMain are all up.
 *
 * // TODO(POC): Replace with Spring Boot {@code FixInitiatorService} in Milestone 9.
 */
public class FixClientMain
{
    private static final String LIBRARY_CHANNEL = "aeron:ipc";
    private static final String ACCEPTOR_HOST   = "localhost";
    private static final int    ACCEPTOR_PORT   = 9880;
    private static final String LOG_DIR         = "./fix-client-logs";

    // ── Artio's private Aeron dir + embedded archive ──────────────────────────
    // Isolated from the shared OMS driver. Artio uses this for its own engine↔library
    // IPC and FIX message replay. Must differ from the acceptor's dir.
    private static final String ARTIO_AERON_DIR  = "./aeron-fix-client";
    private static final String ARTIO_ARCHIVE_DIR = "./fix-client-archive";
    // Fixed ports for Artio's archive — no collision with OMS (8010) or acceptor (8030).
    private static final String ARTIO_ARCHIVE_CONTROL     = "aeron:udp?endpoint=localhost:8020";
    private static final String ARTIO_ARCHIVE_RESPONSE    = "aeron:udp?endpoint=localhost:8021";
    private static final String ARTIO_ARCHIVE_REPLICATION = "aeron:udp?endpoint=localhost:0";

    static final String SENDER_COMP_ID = "CLIENT";
    static final String TARGET_COMP_ID = "ACCEPTOR";

    public static void main(final String[] args) throws Exception
    {
        try
        {
            run(args);
        }
        catch (final Throwable t)
        {
            System.err.println("[Client] FATAL uncaught exception:");
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(final String[] args) throws Exception
    {
        final String artioAeronDirAbsolute = Paths.get(ARTIO_AERON_DIR).toAbsolutePath().normalize().toString();

        // System property controls where Artio's internal Aeron.connect() finds the driver.
        // Must point to Artio's own dir — NOT the shared /tmp/aeron-oms — to avoid:
        //   (a) driver conductor overload causing DriverTimeoutException, and
        //   (b) engine↔library IPC stream conflicts between acceptor and client.
        System.setProperty("aeron.dir", artioAeronDirAbsolute);

        System.err.println("[Client] step 1: launching ArchivingMediaDriver...");
        // ── Step 1: Embedded ArchivingMediaDriver for Artio ───────────────────
        // ThreadingMode.DEDICATED: conductor gets its own thread → CnC heartbeat is always
        // updated on time. SHARED runs conductor+sender+receiver on one thread; on a machine
        // with multiple active JVMs (OmsMediaDriverMain, OmsApp, FixAcceptor) the single thread
        // can be starved for 10+ seconds → DriverTimeoutException during FixEngine.launch().
        final ArchivingMediaDriver artioDriver = ArchivingMediaDriver.launch(
            new MediaDriver.Context()
                .aeronDirectoryName(artioAeronDirAbsolute)
                .dirDeleteOnStart(true)
                .threadingMode(ThreadingMode.DEDICATED),
            new Archive.Context()
                .archiveDir(new File(ARTIO_ARCHIVE_DIR))
                .deleteArchiveOnStart(true)
                .controlChannel(ARTIO_ARCHIVE_CONTROL)
                .replicationChannel(ARTIO_ARCHIVE_REPLICATION)
                .recordingEventsEnabled(false)
                .aeronDirectoryName(artioAeronDirAbsolute)
        );
        System.err.println("[Client] step 2: ArchivingMediaDriver ready");

        // Give the embedded archive time to open its control channel subscription before
        // FixEngine.launch() calls AeronArchive.connect(). Without this pause, the connect
        // request arrives before the archive is listening and AeronArchive.connect() polls
        // indefinitely. The acceptor avoids this by accident — its Aeron.connect() to the
        // OMS driver takes ~500ms which serves as a natural gap. The client has no such gap.
        Thread.sleep(2_000);

        // ── Step 2: Configure FixEngine — no bindTo() for initiator ──────────
        // aeronContext with 30s timeout: belt-and-suspenders alongside DEDICATED threading.
        final EngineConfiguration engineCfg = new EngineConfiguration()
            .libraryAeronChannel(LIBRARY_CHANNEL)
            .logFileDir(LOG_DIR)
            .deleteLogFileDirOnStart(true)
            .scheduler(new LowResourceEngineScheduler());

        // aeronContext() returns the existing Aeron.Context stored inside EngineConfiguration.
        // Mutate it in place — Artio 0.153 has no setter, only the getter.
        engineCfg.aeronContext().driverTimeoutMs(30_000L);

        engineCfg.aeronArchiveContext()
            .controlRequestChannel(ARTIO_ARCHIVE_CONTROL)
            .controlResponseChannel(ARTIO_ARCHIVE_RESPONSE)
            .aeronDirectoryName(artioAeronDirAbsolute);

        // ── Step 3: Launch engine (connects to artioDriver via System.setProperty) ──
        System.err.println("[Client] step 3: launching FixEngine...");
        final FixEngine engine = FixEngine.launch(engineCfg);
        System.err.println("[Client] step 4: FixEngine ready");

        final FixClientSessionHandler sessionHandler = new FixClientSessionHandler();

        final LibraryConfiguration libraryCfg = new LibraryConfiguration()
            .sessionAcquireHandler(sessionHandler)
            .sessionExistsHandler(
                (library, surrogateId, localCompId, localSubId, localLocationId,
                 remoteCompId, remoteSubId, remoteLocationId, logonSeqNum, seqIndex) ->
                    System.err.println(String.format("[Client] Existing session: local=%s remote=%s",
                        localCompId, remoteCompId)))
            .libraryAeronChannels(Collections.singletonList(LIBRARY_CHANNEL));

        System.err.println("[Client] step 5: connecting FixLibrary...");
        final FixLibrary library = FixLibrary.connect(libraryCfg);
        System.err.println("[Client] step 6: FixLibrary connected");

        // Warm-up: poll ~500 ms so the engine's Framer fully registers this library
        // before sending an initiate request. Prevents "Not connected to Gateway" errors.
        System.err.println("[Client] step 7: warm-up polling...");
        for (int i = 0; i < 50; i++)
        {
            library.poll(10);
            Thread.sleep(10);
        }

        // ── Step 4: Initiate FIX session ──────────────────────────────────────
        final SessionConfiguration sessionCfg = SessionConfiguration.builder()
            .address(ACCEPTOR_HOST, ACCEPTOR_PORT)
            .senderCompId(SENDER_COMP_ID)
            .targetCompId(TARGET_COMP_ID)
            // ResetSeqNumFlag (tag 141=Y): tells acceptor to reset its expected inbound seqnum.
            // Required when deleteLogFileDirOnStart=true resets client seqnums to 1 but the
            // acceptor still remembers the previous session's last seqnum.
            // TODO(POC): remove if persistent sequence tracking is needed.
            .resetSeqNum(true)
            .build();

        System.err.println(String.format("[Client] step 8: initiating FIX session → %s:%d (sender=%s target=%s)",
            ACCEPTOR_HOST, ACCEPTOR_PORT, SENDER_COMP_ID, TARGET_COMP_ID));

        final Reply<Session> sessionReply = library.initiate(sessionCfg);
        long lastStatusMs = System.currentTimeMillis();
        while (sessionReply.isExecuting())
        {
            library.poll(10);
            Thread.sleep(1);
            if (System.currentTimeMillis() - lastStatusMs > 2_000)
            {
                System.err.println("[Client] ...waiting for session reply state=" + sessionReply.state());
                lastStatusMs = System.currentTimeMillis();
            }
        }

        if (sessionReply.hasCompleted())
        {
            final Session session = sessionReply.resultIfPresent();
            System.err.println(String.format("[Client] Session acquired: id=%d state=%s",
                session != null ? session.id() : -1L,
                session != null ? session.state() : "null"));
        }
        else
        {
            System.err.println(String.format("[Client] Session initiation failed: state=%s error=%s",
                sessionReply.state(), sessionReply.error()));
            library.close();
            engine.close();
            artioDriver.close();
            return;
        }

        // Shutdown hook: library → engine → artioDriver.
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            System.err.println("[Client] Shutting down — sending Logout.");
            library.close();
            engine.close();
            artioDriver.close();
        }));

        System.err.println("[Client] Session active. Press Ctrl-C to disconnect.");

        while (!Thread.currentThread().isInterrupted())
        {
            library.poll(10);
            Thread.sleep(1);
        }
    }
}
