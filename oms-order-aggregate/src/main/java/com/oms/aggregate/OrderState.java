package com.oms.aggregate;

import com.oms.sbe.OrderType;
import com.oms.sbe.Side;
import com.oms.sbe.TimeInForce;

/**
 * Mutable in-memory state for a single order, owned by the OrderAggregateAgent thread.
 * Not thread-safe by design — only the aggregate agent thread reads/writes it.
 */
public class OrderState {

    public final long orderId;
    public final long accountId;
    public final String instrument;
    public final Side side;
    public final OrderType orderType;
    public final TimeInForce timeInForce;
    public final long price;
    public final long quantity;
    // Mutable — transitions as events are applied
    public OrderStatus status;
    public long currentPrice;       // = price initially; updated on amend
    public long currentQuantity;    // = quantity initially; updated on amend
    public long filledQuantity;     // cumulative fill from observed fill events

    public OrderState(
            long orderId, long accountId, String instrument,
            Side side, OrderType orderType, TimeInForce timeInForce,
            long price, long quantity, OrderStatus status) {
        this.orderId         = orderId;
        this.accountId       = accountId;
        this.instrument      = instrument;
        this.side            = side;
        this.orderType       = orderType;
        this.timeInForce     = timeInForce;
        this.price           = price;
        this.quantity        = quantity;
        this.status          = status;
        this.currentPrice    = price;
        this.currentQuantity = quantity;
        this.filledQuantity  = 0L;
    }
}
