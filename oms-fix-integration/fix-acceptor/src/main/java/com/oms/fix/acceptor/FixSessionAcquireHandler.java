package com.oms.fix.acceptor;

import uk.co.real_logic.artio.library.SessionAcquireHandler;
import uk.co.real_logic.artio.library.SessionAcquiredInfo;
import uk.co.real_logic.artio.library.SessionHandler;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.Session;

/**
 * M3: creates a {@link FixSessionHandler} per session, wired to the Aeron command-stream publisher.
 */
public class FixSessionAcquireHandler implements SessionAcquireHandler
{
    private final CommandStreamPublisher publisher;

    public FixSessionAcquireHandler(final CommandStreamPublisher publisher)
    {
        this.publisher = publisher;
    }

    @Override
    public SessionHandler onSessionAcquired(final Session session, final SessionAcquiredInfo acquiredInfo)
    {
        final CompositeKey key = session.compositeKey();
        System.out.printf("[Acceptor] Session acquired: id=%d local=%s remote=%s%n",
            session.id(), key.localCompId(), key.remoteCompId());
        return new FixSessionHandler(publisher);
    }
}
