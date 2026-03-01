package com.oms.ingress;

import com.epam.deltix.gflog.api.Log;
import com.epam.deltix.gflog.api.LogFactory;
import com.oms.sbe.MessageHeaderEncoder;
import com.oms.sbe.NewOrderCommandEncoder;
import com.oms.sbe.OrderType;
import com.oms.sbe.Side;
import com.oms.sbe.TimeInForce;
import io.aeron.Publication;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Stub ingress that publishes a single hard-coded {@code NewOrderCommand} on first call,
 * then becomes a no-op. Proves the sequencer-to-subscribers pipeline is wired correctly.
 *
 * TODO(POC): replace with a real REST → SBE gateway (Spring Boot or Netty) in Milestone 2.
 */
public class OrderIngressStub implements Agent {

    private static final Log log = LogFactory.getLog(OrderIngressStub.class);

    // Pre-allocate encoding buffer and codecs in the constructor — never in doWork().
    private final UnsafeBuffer encodingBuffer = new UnsafeBuffer(new byte[512]);
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final NewOrderCommandEncoder cmdEncoder = new NewOrderCommandEncoder();

    private final Publication commandIngressPub;
    private boolean published = false;

    public OrderIngressStub(Publication commandIngressPub) {
        this.commandIngressPub = commandIngressPub;
    }

    @Override
    public int doWork() {
        if (published) {
            return 0;
        }
        // Wait until the Sequencer has connected its subscription before publishing.
        // isConnected() returns true once at least one subscriber is ready on this publication.
        if (!commandIngressPub.isConnected()) {
            return 0;
        }

        // Encode the NewOrderCommand. sequenceNumber=0 — the Sequencer will overwrite it.
        cmdEncoder.wrapAndApplyHeader(encodingBuffer, 0, headerEncoder)
                .sequenceNumber(0)
                .timestamp(System.nanoTime())
                .correlationId(1L)
                .orderId(1001L)
                .accountId(42L)
                .instrument("AAPL        ")  // 12 chars, space-padded to match SBE fixed-length field
                .side(Side.BUY)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.DAY)
                .price(150_00000000L)    // 150.00 × 10^8 fixed-point
                .quantity(100_00000000L); // 100.00 × 10^8 fixed-point

        int msgLength = MessageHeaderEncoder.ENCODED_LENGTH + NewOrderCommandEncoder.BLOCK_LENGTH;
        long result = commandIngressPub.offer(encodingBuffer, 0, msgLength);
        if (result > 0) {
            published = true;
            log.info().append("[ingress] published test NewOrderCommand orderId=1001").commit();
            return 1;
        }
        return 0;
    }

    @Override
    public String roleName() { return "order-ingress"; }

    @Override
    public void onStart() {
        log.info().append("order-ingress started").commit();
    }

    @Override
    public void onClose() {
        log.info().append("order-ingress closed").commit();
    }
}
