package com.oms.aggregate.client;

import com.oms.fix.sbe.PlaceOrderCommandDecoder;
import com.oms.sbe.NewOrderCommandDecoder;

public interface OrderCommandApi {

    void handleNewOrder(NewOrderCommandDecoder commandDecoder);

    void handlePlaceOrder(PlaceOrderCommandDecoder commandDecoder);
}
