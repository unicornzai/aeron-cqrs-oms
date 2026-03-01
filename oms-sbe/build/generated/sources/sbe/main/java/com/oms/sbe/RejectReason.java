/* Generated SBE (Simple Binary Encoding) message codec. */
package com.oms.sbe;

@SuppressWarnings("all")
public enum RejectReason
{
    INVALID_PRICE((short)0),

    INVALID_QUANTITY((short)1),

    UNKNOWN_INSTRUMENT((short)2),

    UNKNOWN_ACCOUNT((short)3),

    RISK_BREACH((short)4),

    DUPLICATE_ORDER((short)5),

    /**
     * To be used to represent not present or null.
     */
    NULL_VAL((short)255);

    private final short value;

    RejectReason(final short value)
    {
        this.value = value;
    }

    /**
     * The raw encoded value in the Java type representation.
     *
     * @return the raw value encoded.
     */
    public short value()
    {
        return value;
    }

    /**
     * Lookup the enum value representing the value.
     *
     * @param value encoded to be looked up.
     * @return the enum value representing the value.
     */
    public static RejectReason get(final short value)
    {
        switch (value)
        {
            case 0: return INVALID_PRICE;
            case 1: return INVALID_QUANTITY;
            case 2: return UNKNOWN_INSTRUMENT;
            case 3: return UNKNOWN_ACCOUNT;
            case 4: return RISK_BREACH;
            case 5: return DUPLICATE_ORDER;
            case 255: return NULL_VAL;
        }

        throw new IllegalArgumentException("Unknown value: " + value);
    }
}
