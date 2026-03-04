package com.oms.fix.acceptor;

import io.aeron.Publication;
import org.agrona.DirectBuffer;

/**
 * Wraps an Aeron {@link Publication} with bounded spin-retry.
 * NOT_CONNECTED / BACK_PRESSURED → spin up to MAX_OFFER_ATTEMPTS then drop.
 * CLOSED → fatal; throws immediately.
 */
public final class CommandStreamPublisher
{
    private static final int MAX_OFFER_ATTEMPTS = 100;
    private final Publication publication;

    public CommandStreamPublisher(final Publication publication)
    {
        this.publication = publication;
    }

    public void offer(final DirectBuffer buffer, final int offset, final int length)
    {
        for (int attempt = 0; attempt < MAX_OFFER_ATTEMPTS; attempt++)
        {
            final long result = publication.offer(buffer, offset, length);
            if (result > 0)
            {
                return;
            }
            if (result == Publication.CLOSED)
            {
                throw new IllegalStateException("Publication is CLOSED — cannot publish command");
            }
            // NOT_CONNECTED or BACK_PRESSURED: spin
        }
        // TODO(POC): replace with structured logging
        System.err.printf("[CommandStream] Drop after %d attempts — back-pressure%n", MAX_OFFER_ATTEMPTS);
    }
}
