package com.oms.fix.acceptor;

import com.oms.common.OmsStreams;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.engine.LowResourceEngineScheduler;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;

import java.io.File;
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
 * <p>Run before {@link FixClientMain}. Press Ctrl-C for a clean Logout.
 *
 * // TODO(POC): When integrating with OmsApp, share the single ArchivingMediaDriver
 * //            and coordinate archive channel ports (8010) with the OMS archive (8010).
 * //            Two archives on the same port will conflict.
 */
public class FixAcceptorMain
{
    // Artio engine↔library channel — IPC since both run in the same JVM process.
    static final String LIBRARY_CHANNEL = "aeron:ipc";
    static final int FIX_PORT = 9880;
    private static final String LOG_DIR = "./fix-acceptor-logs";
    private static final String ARCHIVE_DIR = "./fix-acceptor-archive";

    // Artio's internal archive — acceptor uses port 8010.
    // Client uses 8020 so the two processes don't conflict on the same machine.
    private static final String ARCHIVE_CONTROL_CHANNEL  = "aeron:udp?endpoint=localhost:8010";
    private static final String ARCHIVE_REPLICATION_CHANNEL = "aeron:udp?endpoint=localhost:0";

    public static void main(final String[] args) throws Exception
    {
        // Step 1: Launch ArchivingMediaDriver.
        // Artio's FixEngine calls Aeron.connect() — it does NOT start its own driver.
        // The archive is required for Artio's internal message replay (resend requests).
        // ThreadingMode.SHARED runs sender/receiver/conductor on a single thread — POC only.
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .threadingMode(ThreadingMode.SHARED);

        final Archive.Context archiveCtx = new Archive.Context()
            .archiveDir(new File(ARCHIVE_DIR))
            .deleteArchiveOnStart(true)
            .controlChannel(ARCHIVE_CONTROL_CHANNEL)
            .replicationChannel(ARCHIVE_REPLICATION_CHANNEL)
            .recordingEventsEnabled(false);  // no need to broadcast recording events in POC

        final ArchivingMediaDriver archivingMediaDriver =
            ArchivingMediaDriver.launch(driverCtx, archiveCtx);

        // Step 2: Connect Aeron and open command-ingress publication.
        // This Aeron.connect() shares the ArchivingMediaDriver started above.
        final Aeron aeron = Aeron.connect();
        final Publication commandPub = aeron.addPublication(OmsStreams.IPC, OmsStreams.COMMAND_INGRESS_STREAM);
        final CommandStreamPublisher publisher = new CommandStreamPublisher(commandPub);

        // Step 3: Configure FixEngine — point aeronArchiveContext at the archive we just started.
        final EngineConfiguration engineCfg = new EngineConfiguration()
            .bindTo("0.0.0.0", FIX_PORT)
            .libraryAeronChannel(LIBRARY_CHANNEL)
            .logFileDir(LOG_DIR)
            .deleteLogFileDirOnStart(true)
            // LowResourceEngineScheduler collapses framer/sender/receiver threads — POC only.
            .scheduler(new LowResourceEngineScheduler());

        engineCfg.aeronArchiveContext()
            .controlRequestChannel(ARCHIVE_CONTROL_CHANNEL)
            .controlResponseChannel("aeron:udp?endpoint=localhost:0");  // ephemeral response port

        // Step 4: Launch engine (connects to the running ArchivingMediaDriver).
        final FixEngine engine = FixEngine.launch(engineCfg);

        // Step 5: Connect FixLibrary (connects to engine via IPC).
        final FixSessionAcquireHandler acquireHandler = new FixSessionAcquireHandler(publisher);

        final LibraryConfiguration libraryCfg = new LibraryConfiguration()
            .sessionAcquireHandler(acquireHandler)
            .sessionExistsHandler(
                (library, surrogateId, localCompId, localSubId, localLocationId,
                 remoteCompId, remoteSubId, remoteLocationId, logonSeqNum, seqIndex) ->
                    System.out.printf("[Acceptor] Existing session: remote=%s%n", remoteCompId))
            .libraryAeronChannels(Collections.singletonList(LIBRARY_CHANNEL));

        final FixLibrary library = FixLibrary.connect(libraryCfg);

        // Shutdown: close in reverse order — library → Aeron → engine → archivingMediaDriver.
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            System.out.println("[Acceptor] Shutting down — Artio will send Logout to all sessions.");
            library.close();
            commandPub.close();
            aeron.close();
            engine.close();
            archivingMediaDriver.close();
        }));

        System.out.println("[Acceptor] Listening on port " + FIX_PORT + ". Press Ctrl-C to stop.");

        while (!Thread.currentThread().isInterrupted())
        {
            library.poll(10);
        }
    }
}
