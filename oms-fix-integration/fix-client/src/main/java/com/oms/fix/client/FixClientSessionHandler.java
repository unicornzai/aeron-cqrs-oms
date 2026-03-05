package com.oms.fix.client;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.builder.NewOrderSingleEncoder;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.library.OnMessageInfo;
import uk.co.real_logic.artio.library.SessionAcquireHandler;
import uk.co.real_logic.artio.library.SessionAcquiredInfo;
import uk.co.real_logic.artio.library.SessionHandler;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.Session;

/**
 * M3: sends one hardcoded NewOrderSingle immediately on Logon.
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
        // Reuse encoder and timestamp encoder — single-threaded library polling.
        private final NewOrderSingleEncoder nos = new NewOrderSingleEncoder();
        private final UtcTimestampEncoder tsEncoder = new UtcTimestampEncoder();

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
            // M3: no inbound application messages expected (acceptor sends none).
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
            final CompositeKey key = session.compositeKey();
            System.out.printf("[Client] Logon complete: local=%s remote=%s%n",
                    key.localCompId(), key.remoteCompId());

            // Build a hardcoded NOS: AAPL BUY 100 @ 185.50 LIMIT
            nos.reset();
            nos.clOrdID("FIX-NOS-001");
//            nos.handlInst('3');                             // FIX 4.4 tag 21: '3'=Manual, required field
            nos.instrument().symbol("AAPL");
            nos.side('1');                              // FIX 4.4 tag 54: '1'=BUY
            nos.ordType('2');                           // FIX 4.4 tag 40: '2'=LIMIT
            nos.price(18550L, -2);                      // 18550 × 10^-2 = 185.50
            nos.orderQtyData().orderQty(100L, 0);       // 100 × 10^0 = 100

            // TransactTime (tag 60) — required in FIX 4.4 NOS
            final int tsLen = tsEncoder.encode(System.currentTimeMillis());
            nos.transactTime(tsEncoder.buffer(), 0, tsLen);

            final long result = session.trySend(nos);
            System.out.printf("[Client] Sent NOS FIX-NOS-001: trySend result=%d%n", result);
        }
    }
}