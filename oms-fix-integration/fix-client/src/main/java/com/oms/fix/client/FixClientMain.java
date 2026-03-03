package com.oms.fix.client;

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
import java.util.Collections;

/**
 * M2: Artio FIX initiator — connects to {@link FixAcceptorMain} on port 9880.
 *
 * <p>Establishes a FIX 4.4 session (Logon 35=A), maintains heartbeats automatically,
 * and disconnects cleanly (Logout 35=5) on Ctrl-C.
 *
 * <p>Run <em>after</em> the acceptor is up. Each process has its own
 * {@link ArchivingMediaDriver}; the client uses archive port 8020 to avoid
 * conflicting with the acceptor's archive on port 8010.
 *
 * // TODO(POC): Replace with Spring Boot {@code FixInitiatorService} in Milestone 9.
 */
public class FixClientMain
{
    private static final String LIBRARY_CHANNEL = "aeron:ipc";
    private static final String ACCEPTOR_HOST   = "localhost";
    private static final int    ACCEPTOR_PORT   = 9880;
    private static final String LOG_DIR         = "./fix-client-logs";
    private static final String ARCHIVE_DIR     = "./fix-client-archive";

    // Client archive on port 8020 — distinct from acceptor's 8010.
    private static final String ARCHIVE_CONTROL_CHANNEL     = "aeron:udp?endpoint=localhost:8020";
    private static final String ARCHIVE_REPLICATION_CHANNEL = "aeron:udp?endpoint=localhost:0";

    static final String SENDER_COMP_ID = "CLIENT";
    static final String TARGET_COMP_ID = "ACCEPTOR";

    public static void main(final String[] args) throws Exception
    {
        // Step 1: Launch ArchivingMediaDriver for this process.
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .threadingMode(ThreadingMode.SHARED);

        final Archive.Context archiveCtx = new Archive.Context()
            .archiveDir(new File(ARCHIVE_DIR))
            .deleteArchiveOnStart(true)
            .controlChannel(ARCHIVE_CONTROL_CHANNEL)
            .replicationChannel(ARCHIVE_REPLICATION_CHANNEL)
            .recordingEventsEnabled(false);

        final ArchivingMediaDriver archivingMediaDriver =
            ArchivingMediaDriver.launch(driverCtx, archiveCtx);

        // Step 2: Configure FixEngine — initiator has no bindTo().
        final EngineConfiguration engineCfg = new EngineConfiguration()
            .libraryAeronChannel(LIBRARY_CHANNEL)
            .logFileDir(LOG_DIR)
            .deleteLogFileDirOnStart(true)
            .scheduler(new LowResourceEngineScheduler());

        engineCfg.aeronArchiveContext()
            .controlRequestChannel(ARCHIVE_CONTROL_CHANNEL)
            .controlResponseChannel("aeron:udp?endpoint=localhost:0");

        // Step 3: Launch engine then library.
        final FixEngine engine = FixEngine.launch(engineCfg);

        final FixClientSessionHandler sessionHandler = new FixClientSessionHandler();

        final LibraryConfiguration libraryCfg = new LibraryConfiguration()
            .sessionAcquireHandler(sessionHandler)
            .sessionExistsHandler(
                (library, surrogateId, localCompId, localSubId, localLocationId,
                 remoteCompId, remoteSubId, remoteLocationId, logonSeqNum, seqIndex) ->
                    System.out.printf("[Client] Existing session: local=%s remote=%s%n",
                        localCompId, remoteCompId))
            .libraryAeronChannels(Collections.singletonList(LIBRARY_CHANNEL));

        final FixLibrary library = FixLibrary.connect(libraryCfg);

        // Step 4: Initiate the FIX session — non-blocking; poll until complete.
        final SessionConfiguration sessionCfg = SessionConfiguration.builder()
            .address(ACCEPTOR_HOST, ACCEPTOR_PORT)
            .senderCompId(SENDER_COMP_ID)
            .targetCompId(TARGET_COMP_ID)
            .build();

        System.out.printf("[Client] Initiating FIX session → %s:%d (sender=%s target=%s)%n",
            ACCEPTOR_HOST, ACCEPTOR_PORT, SENDER_COMP_ID, TARGET_COMP_ID);

        final Reply<Session> sessionReply = library.initiate(sessionCfg);
        while (sessionReply.isExecuting())
        {
            library.poll(10);
            Thread.sleep(1);
        }

        if (sessionReply.hasCompleted())
        {
            final Session session = sessionReply.resultIfPresent();
            System.out.printf("[Client] Logon complete: sessionId=%d state=%s%n",
                session != null ? session.id() : -1L,
                session != null ? session.state() : "null");
        }
        else
        {
            System.err.printf("[Client] Session initiation failed: state=%s error=%s%n",
                sessionReply.state(), sessionReply.error());
            library.close();
            engine.close();
            archivingMediaDriver.close();
            return;
        }

        // Shutdown hook: close in reverse order — library → engine → archivingMediaDriver.
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            System.out.println("[Client] Shutting down — sending Logout.");
            library.close();
            engine.close();
            archivingMediaDriver.close();
        }));

        System.out.println("[Client] Session active. Press Ctrl-C to disconnect.");

        // Keep polling: drives heartbeat generation and inbound message processing.
        while (!Thread.currentThread().isInterrupted())
        {
            library.poll(10);
            Thread.sleep(1);
        }
    }
}
