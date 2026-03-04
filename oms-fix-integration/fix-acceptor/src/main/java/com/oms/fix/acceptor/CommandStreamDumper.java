package com.oms.fix.acceptor;

import com.oms.common.OmsStreams;
import com.oms.fix.sbe.Decimal64Decoder;
import com.oms.fix.sbe.MessageHeaderDecoder;
import com.oms.fix.sbe.NewOrderSingleCommandDecoder;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;

import java.nio.file.Paths;

/**
 * Throwaway standalone subscriber — no Artio, pure Aeron + SBE.
 * Connects to the acceptor's ArchivingMediaDriver (./aeron-fix-acceptor),
 * subscribes to IPC stream 10, and prints each NewOrderSingleCommand.
 *
 * // TODO(POC): remove or fold into an integration test once M4 Sequencer is wired.
 */
public final class CommandStreamDumper
{
    public static void main(final String[] args) throws Exception
    {
        // Must match FixAcceptorMain.AERON_DIR — the acceptor's driver lives there, not in the default dir.
        final String aeronDirAbsolute = Paths.get("./aeron-fix-acceptor").toAbsolutePath().normalize().toString();
        final Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDirAbsolute));
        final Subscription sub = aeron.addSubscription(OmsStreams.IPC, OmsStreams.COMMAND_INGRESS_STREAM);

        final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        final NewOrderSingleCommandDecoder nosDecoder = new NewOrderSingleCommandDecoder();

        final FragmentHandler handler = (buffer, offset, length, header) ->
        {
            headerDecoder.wrap(buffer, offset);

            if (headerDecoder.templateId() != NewOrderSingleCommandDecoder.TEMPLATE_ID)
            {
                System.out.println("template not NOS: " + headerDecoder.templateId());
                return;
            }
            else { System.out.println("Received unknown template: " + headerDecoder.templateId()); }

            nosDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

            final Decimal64Decoder price    = nosDecoder.price();
            final Decimal64Decoder orderQty = nosDecoder.orderQty();

            System.out.printf(
                "[Dumper] NOS seqNum=%d sessionId=%d clOrdId=%s symbol=%s side=%s qty=%de%d price=%de%d%n",
                nosDecoder.sequenceNumber(),
                nosDecoder.sessionId(),
                nosDecoder.clOrdId(),
                nosDecoder.symbol(),
                nosDecoder.side(),
                orderQty.mantissa(), orderQty.exponent(),
                price.mantissa(),    price.exponent());
        };

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            sub.close();
            aeron.close();
        }));

        System.out.println("[Dumper] Listening on aeron:ipc stream " + OmsStreams.COMMAND_INGRESS_STREAM + "...");
        while (!Thread.currentThread().isInterrupted())
        {
            sub.poll(handler, 10);
            Thread.sleep(1);
        }
    }
}
