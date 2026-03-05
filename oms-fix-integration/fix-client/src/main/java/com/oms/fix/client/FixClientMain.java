package com.oms.fix.client;

import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.engine.LowResourceEngineScheduler;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.library.SessionConfiguration;
import uk.co.real_logic.artio.session.Session;

import java.util.Collections;

/**
 * M2: Artio FIX initiator — connects to {@link FixAcceptorMain} on port 9880.
 *
 * <p>Requires {@link com.oms.driver.OmsMediaDriverMain} to be running first.
 * Shares the Aeron dir at {@value #AERON_DIR} and archive on port 8010.
 *
 * <p>Establishes a FIX 4.4 session (Logon 35=A), maintains heartbeats automatically,
 * and disconnects cleanly (Logout 35=5) on Ctrl-C.
 *
 * // TODO(POC): Replace with Spring Boot {@code FixInitiatorService} in Milestone 9.
 */
public class FixClientMain
{
    private static final String LIBRARY_CHANNEL = "aeron:ipc";
    private static final String ACCEPTOR_HOST   = "localhost";
    private static final int    ACCEPTOR_PORT   = 9880;
    private static final String LOG_DIR         = "./fix-client-logs";

    // Shared Aeron dir — must match OmsMediaDriverMain.AERON_DIR
    private static final String AERON_DIR = "/tmp/aeron-oms";

    // All processes share the single archive on port 8010 (run by OmsMediaDriverMain).
    // Artio's internal archive client needs a fixed response port — ephemeral :0 can hang.
    // 8021 is dedicated to this process's archive responses; no collision with acceptor (8031).
    private static final String ARCHIVE_CONTROL_CHANNEL  = "aeron:udp?endpoint=localhost:8010";
    private static final String ARCHIVE_RESPONSE_CHANNEL = "aeron:udp?endpoint=localhost:8021";

    static final String SENDER_COMP_ID = "CLIENT";
    static final String TARGET_COMP_ID = "ACCEPTOR";

    public static void main(final String[] args) throws Exception
    {
        // Force Artio's internal Aeron.connect() calls to use the shared driver dir.
        System.setProperty("aeron.dir", AERON_DIR);

        // Step 1: Configure FixEngine — initiator has no bindTo().
        // Connects to the shared driver (OmsMediaDriverMain must be running).
        final EngineConfiguration engineCfg = new EngineConfiguration()
            .libraryAeronChannel(LIBRARY_CHANNEL)
            .logFileDir(LOG_DIR)
            .deleteLogFileDirOnStart(true)
            .scheduler(new LowResourceEngineScheduler());

        engineCfg.aeronArchiveContext()
            .controlRequestChannel(ARCHIVE_CONTROL_CHANNEL)   // shared archive UDP 8010
            .controlResponseChannel(ARCHIVE_RESPONSE_CHANNEL) // fixed port 8021 — avoids ephemeral hang
            .aeronDirectoryName(AERON_DIR);

        // Step 2: Launch engine then library.
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

        // Warm-up: poll ~500 ms to let the engine's Framer fully register this library
        // before sending an initiate request. Without this, initiate() can immediately
        // error with "Not connected to the Gateway" on a fast startup path.
        for (int i = 0; i < 50; i++)
        {
            library.poll(10);
            Thread.sleep(10);
        }

        // Step 3: Initiate the FIX session — non-blocking; poll until complete.
        final SessionConfiguration sessionCfg = SessionConfiguration.builder()
            .address(ACCEPTOR_HOST, ACCEPTOR_PORT)
            .senderCompId(SENDER_COMP_ID)
            .targetCompId(TARGET_COMP_ID)
            // ResetSeqNumFlag (tag 141=Y): tells the acceptor to reset its expected inbound
            // sequence number on every Logon. Required when the client process restarts and
            // its log dir is deleted (deleteLogFileDirOnStart=true resets client seqnums to 1,
            // but the acceptor still remembers the previous session's last seqnum).
            // TODO(POC): remove if persistent sequence tracking is needed across client restarts.
            .resetSeqNum(true)
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
            return;
        }

        // Shutdown hook: close in reverse order — library → engine.
        // Driver is managed by OmsMediaDriverMain — do NOT close it here.
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            System.out.println("[Client] Shutting down — sending Logout.");
            library.close();
            engine.close();
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
