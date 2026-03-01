package com.oms.readmodel.view;

/**
 * Immutable snapshot of an order as seen by the view layer.
 * Use {@link #withFill} to derive a filled copy.
 *
 * <p>Price and quantity are fixed-point (× 10^8) matching the SBE wire format.
 */
public final class OrderView {

    public final long orderId;
    public final long accountId;
    public final String instrument;
    public final String side;
    public final String orderType;
    public final String tif;
    public final long price;
    public final long quantity;
    public final OrderStatus status;
    public final long fillPrice;
    public final long fillQuantity;

    public OrderView(long orderId, long accountId, String instrument,
                     String side, String orderType, String tif,
                     long price, long quantity, OrderStatus status) {
        this(orderId, accountId, instrument, side, orderType, tif,
             price, quantity, status, 0L, 0L);
    }

    private OrderView(long orderId, long accountId, String instrument,
                      String side, String orderType, String tif,
                      long price, long quantity, OrderStatus status,
                      long fillPrice, long fillQuantity) {
        this.orderId      = orderId;
        this.accountId    = accountId;
        this.instrument   = instrument;
        this.side         = side;
        this.orderType    = orderType;
        this.tif          = tif;
        this.price        = price;
        this.quantity     = quantity;
        this.status       = status;
        this.fillPrice    = fillPrice;
        this.fillQuantity = fillQuantity;
    }

    /** Returns a new OrderView with status=FILLED and the given fill details. */
    public OrderView withFill(long fillPrice, long fillQuantity) {
        return new OrderView(orderId, accountId, instrument, side, orderType, tif,
                             price, quantity, OrderStatus.FILLED, fillPrice, fillQuantity);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(128);
        sb.append("OrderView{orderId=").append(orderId)
          .append(" status=").append(status)
          .append(" price=").append(price / 1e8)
          .append(" qty=").append(quantity / 1e8);
        if (fillPrice > 0) {
            sb.append(" fillPrice=").append(fillPrice / 1e8)
              .append(" fillQty=").append(fillQuantity / 1e8);
        }
        sb.append('}');
        return sb.toString();
    }
}
