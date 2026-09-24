package io.pockethive.redis.api;

/**
 * Responsibility: describe a sequence token and calculate its cardinality.
 * Must not: access Redis or resolve connection settings.
 * Contract: RESP-TEMPLATE-SEQUENCE — docs/architecture/runtime-responsibilities.md#resp-template-sequence.
 */
record SequenceToken(char type, int width, boolean zeroPad, String literal) {
    long mod(SequenceMode mode) {
        if (literal != null) {
            return 1L;
        }
        int base = type == 'd' ? 10 : mode.base;
        return fastPow(base, width);
    }
    private static final long[] POW10 = {1,10,100,1000,10000,100000,1000000,10000000,100000000,1000000000,
        10000000000L,100000000000L,1000000000000L,10000000000000L,100000000000000L,1000000000000000L,
        10000000000000000L,100000000000000000L,1000000000000000000L};
    private static final long[] POW26 = precompute(26, 13);
    private static final long[] POW36 = precompute(36, 12);
    private static long[] precompute(int base, int max) {
        long[] p = new long[max + 1];
        p[0] = 1;
        for (int i = 1; i <= max; i++) p[i] = p[i-1] * base;
        return p;
    }

    private static long fastPow(int base, int exp) {
        if (exp == 0) return 1;
        if (base == 2) return 1L << exp;
        if (base == 16) return 1L << (exp << 2);
        if (base == 10 && exp < POW10.length) return POW10[exp];
        if (base == 26 && exp < POW26.length) return POW26[exp];
        if (base == 36 && exp < POW36.length) return POW36[exp];

        long result = 1, b = base;
        while (exp > 0) {
            if ((exp & 1) == 1) result *= b;
            b *= b;
            exp >>= 1;
        }
        return result;
    }

}
