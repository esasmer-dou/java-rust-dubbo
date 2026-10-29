package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.runtime.DubboCodecException;

/** Range checks shared by generated codecs, not a second Hessian parser. */
public final class NativeProviderCodec {
    private NativeProviderCodec() {}

    /** The published writer compacts both signed zeroes to positive zero. Preserve the wire sign. */
    public static void writeDouble(com.reactor.rust.dubbo.runtime.Hessian2Output out, double value) {
        if (Double.doubleToRawLongBits(value) == Long.MIN_VALUE) {
            // Delegate capacity/growth to the owner; NaN reserves a complete Hessian 'D' value.
            out.writeDouble(Double.NaN);
            out.buffer().putLong(out.position() - Long.BYTES, Long.MIN_VALUE);
        } else {
            out.writeDouble(value);
        }
    }
    public static int checkedInt(int value, int min, int max) {
        if (value < min || value > max) throw new DubboCodecException("Integer outside declared type");
        return value;
    }
    public static void collectionSize(int size, int maximum) {
        if (size < 0 || size > maximum) throw new DubboCodecException("Collection exceeds generated bound");
    }
}
