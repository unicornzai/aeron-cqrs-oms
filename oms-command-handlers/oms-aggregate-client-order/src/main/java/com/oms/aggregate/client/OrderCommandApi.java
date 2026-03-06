package com.oms.aggregate.client;

import com.oms.sbe.NewOrderCommandDecoder;

public interface OrderCommandApi {

    void handleNewOrder(NewOrderCommandDecoder commandDecoder);
}
