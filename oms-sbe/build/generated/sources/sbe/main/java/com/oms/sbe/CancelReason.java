/* Generated SBE (Simple Binary Encoding) message codec. */
package com.oms.sbe;

@SuppressWarnings("all")
public enum CancelReason
{
    CLIENT_REQUEST((short)0),

    RISK_BREACH((short)1),

    EXPIRED((short)2),

    /**
     * To be used to represent not present or null.
     */
    NULL_VAL((short)255);

    private final short value;

    CancelReason(final short value)
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
    public static CancelReason get(final short value)
    {
        switch (value)
        {
            case 0: return CLIENT_REQUEST;
            case 1: return RISK_BREACH;
            case 2: return EXPIRED;
            case 255: return NULL_VAL;
        }

        throw new IllegalArgumentException("Unknown value: " + value);
    }
}
