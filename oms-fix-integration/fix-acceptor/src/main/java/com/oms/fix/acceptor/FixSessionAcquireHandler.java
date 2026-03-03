package com.oms.fix.acceptor;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.library.OnMessageInfo;
import uk.co.real_logic.artio.library.SessionAcquireHandler;
import uk.co.real_logic.artio.library.SessionAcquiredInfo;
import uk.co.real_logic.artio.library.SessionHandler;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.Session;

/**
 * M2 stub: logs all FIX session lifecycle events on the acceptor side.
 *
 * <p>M3+: will encode SBE {@code NewOrderSingleCommand} / {@code OrderCancelRequestCommand}
 * messages from {@code onMessage()} and publish them to the Aeron Command Stream.
 */
public class FixSessionAcquireHandler implements SessionAcquireHandler
{
    @Override
    public SessionHandler onSessionAcquired(final Session session, final SessionAcquiredInfo acquiredInfo)
    {
        final CompositeKey key = session.compositeKey();
        System.out.printf("[Acceptor] Session acquired: id=%d local=%s remote=%s%n",
            session.id(), key.localCompId(), key.remoteCompId());
        return new LoggingSessionHandler();
    }

    private static final class LoggingSessionHandler implements SessionHandler
    {
        @Override
        public Action onMessage(
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final int libraryId,
            final Session session,
            final int sequenceIndex,
            final long messageType,
            final long timestampInNs,
            final long position,
            final OnMessageInfo messageInfo)
        {
            // M2: Artio routes session messages (Logon/Logout/Heartbeat) internally;
            // only application messages reach this callback. None expected in M2.
            // M3+: dispatch on messageType == NewOrderSingle / OrderCancelRequest.
            return Action.CONTINUE;
        }

        @Override
        public void onTimeout(final int libraryId, final Session session)
        {
            System.out.printf("[Acceptor] Session timeout: libraryId=%d sessionId=%d%n",
                libraryId, session.id());
        }

        @Override
        public void onSlowStatus(final int libraryId, final Session session, final boolean hasBecomeSlow)
        {
            System.out.printf("[Acceptor] Slow-consumer: hasBecomeSlow=%b sessionId=%d%n",
                hasBecomeSlow, session.id());
        }

        @Override
        public Action onDisconnect(final int libraryId, final Session session, final DisconnectReason reason)
        {
            // Called after Artio has sent Logout (35=5) and the TCP connection closes.
            System.out.printf("[Acceptor] Disconnected: sessionId=%d reason=%s%n",
                session.id(), reason);
            return Action.CONTINUE;
        }

        @Override
        public void onSessionStart(final Session session)
        {
            // Called when Logon (35=A) exchange is complete and the session is live.
            final CompositeKey key = session.compositeKey();
            System.out.printf("[Acceptor] Logon complete: local=%s remote=%s%n",
                key.localCompId(), key.remoteCompId());
        }
    }
}
