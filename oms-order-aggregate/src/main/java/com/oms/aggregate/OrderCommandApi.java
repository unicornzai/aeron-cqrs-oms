package com.oms.aggregate;

import com.oms.fix.sbe.PlaceOrderCommandDecoder;
import com.oms.sbe.NewOrderCommandDecoder;
import org.agrona.DirectBuffer;

public interface OrderCommandApi {

    void handleNewOrder(NewOrderCommandDecoder commandDecoder);

    void handlePlaceOrder(PlaceOrderCommandDecoder commandDecoder);
}
