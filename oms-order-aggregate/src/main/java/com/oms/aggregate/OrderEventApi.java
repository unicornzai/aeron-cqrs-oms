package com.oms.aggregate;

import com.oms.sbe.*;

public interface OrderEventApi {

    void applyOrderAcceptedEvent(OrderAcceptedEventDecoder eventDecoder);
    void applyOrderRejectedEvent(OrderRejectedEventDecoder eventDecoder);
    void applyCancelRejectedEvent(CancelRejectedEventDecoder eventDecoder);
    void applyOrderCancelledEvent(OrderCancelledEventDecoder eventDecoder);
    void applyOrderAmmendedEvent(OrderAmendedEventDecoder eventDecoder);
}
