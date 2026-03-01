/* Generated SBE (Simple Binary Encoding) message codec. */
package com.oms.sbe;

@SuppressWarnings("all")
public enum TimeInForce
{
    DAY((short)0),

    GTC((short)1),

    IOC((short)2),

    FOK((short)3),

    /**
     * To be used to represent not present or null.
     */
    NULL_VAL((short)255);

    private final short value;

    TimeInForce(final short value)
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
    public static TimeInForce get(final short value)
    {
        switch (value)
        {
            case 0: return DAY;
            case 1: return GTC;
            case 2: return IOC;
            case 3: return FOK;
            case 255: return NULL_VAL;
        }

        throw new IllegalArgumentException("Unknown value: " + value);
    }
}
