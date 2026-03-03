package com.oms.fix.client;

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
 * M2 stub: logs all FIX session lifecycle events on the initiator side.
 *
 * <p>M7+: {@code onMessage()} will parse ExecutionReport (35=8) messages and dispatch
 * them to the SSE emitter map keyed by {@code clOrdId}.
 */
public class FixClientSessionHandler implements SessionAcquireHandler
{
    @Override
    public SessionHandler onSessionAcquired(final Session session, final SessionAcquiredInfo acquiredInfo)
    {
        final CompositeKey key = session.compositeKey();
        System.out.printf("[Client] Session acquired: id=%d local=%s remote=%s%n",
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
            // M2: no application messages expected.
            // M7+: check messageType == ExecutionReport message type and decode.
            return Action.CONTINUE;
        }

        @Override
        public void onTimeout(final int libraryId, final Session session)
        {
            System.out.printf("[Client] Session timeout: libraryId=%d sessionId=%d%n",
                libraryId, session.id());
        }

        @Override
        public void onSlowStatus(final int libraryId, final Session session, final boolean hasBecomeSlow)
        {
            System.out.printf("[Client] Slow-consumer: hasBecomeSlow=%b sessionId=%d%n",
                hasBecomeSlow, session.id());
        }

        @Override
        public Action onDisconnect(final int libraryId, final Session session, final DisconnectReason reason)
        {
            System.out.printf("[Client] Disconnected: sessionId=%d reason=%s%n",
                session.id(), reason);
            return Action.CONTINUE;
        }

        @Override
        public void onSessionStart(final Session session)
        {
            // Fired when Logon (35=A) exchange is complete.
            final CompositeKey key = session.compositeKey();
            System.out.printf("[Client] Logon complete: local=%s remote=%s%n",
                key.localCompId(), key.remoteCompId());
        }
    }
}
