package io.pockethive.redis.api;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Responsibility: preserve sequence mode, format, offset and wrap calculations.
 * Must not: access Redis or select connection settings.
 * Contract: RESP-TEMPLATE-SEQUENCE — docs/architecture/runtime-responsibilities.md#resp-template-sequence.
 */
final class SequenceFormatter {
    private static final ConcurrentHashMap<String, Long> MAX_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, SequencePattern> FORMAT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, SequenceMode> MODE_CACHE = new ConcurrentHashMap<>();
    private final SequenceMode mode;
    private final SequencePattern format;
    private final String cacheKey;
    private SequenceFormatter(SequenceMode mode, SequencePattern format, String cacheKey) {
        this.mode = mode;
        this.format = format;
        this.cacheKey = cacheKey;
    }
    static SequenceFormatter prepare(String mode, String format) {
        return new SequenceFormatter(MODE_CACHE.computeIfAbsent(mode, SequenceMode::parse),
            FORMAT_CACHE.computeIfAbsent(format, SequencePattern::parse), (mode + ":" + format).intern());
    }
    String format(long value, long startOffset, long maxSequence) {
        long adjusted = value + startOffset - 1;
        long max = maxSequence > 0 ? maxSequence : MAX_CACHE.computeIfAbsent(cacheKey, k -> format.calculateMax(mode));
        if (max > 0) {
            adjusted = isPowerOfTwo(max) ? ((adjusted - 1) & (max - 1)) + 1 : ((adjusted - 1) % max) + 1;
        }
        return formatSequence(adjusted, mode, format);
    }
    private static String formatSequence(long value, SequenceMode mode, SequencePattern parsed) {
        StringBuilder result = new StringBuilder(parsed.capacity());
        long seq = value - 1;
        long[] segments = new long[parsed.tokens().length];
        // Assign sequence segments from rightmost token to leftmost (odometer behavior).
        for (int i = parsed.tokens().length - 1; i >= 0; i--) {
            SequenceToken token = parsed.tokens()[i];
            if (token.literal() != null) {
                continue;
            }
            long mod = token.mod(mode);
            long segment = seq % mod;
            seq /= mod;
            segments[i] = segment;
        }

        for (int i = 0; i < parsed.tokens().length; i++) {
            SequenceToken token = parsed.tokens()[i];
            if (token.literal() != null) {
                result.append(token.literal());
                continue;
            }
            long segment = segments[i];
            switch (token.type()) {
                case 'S' -> result.append(encode(segment, mode.upperChars, token.width()));
                case 's' -> result.append(encode(segment, mode.lowerChars, token.width()));
                case 'd' -> {
                    if (token.zeroPad()) {
                        appendZeroPadded(result, segment, token.width());
                    } else {
                        result.append(segment);
                    }
                }
            }
        }
        return result.toString();
    }

    private static void appendZeroPadded(StringBuilder sb, long value, int width) {
        String s = Long.toString(value);
        for (int i = s.length(); i < width; i++) sb.append('0');
        sb.append(s);
    }

    private static String encode(long value, char[] charset, int width) {
        char[] result = new char[width];
        int base = charset.length;

        if (isPowerOfTwo(base)) {
            int shift = Integer.numberOfTrailingZeros(base);
            int mask = base - 1;
            for (int i = width - 1; i >= 0; i--) {
                result[i] = charset[(int)(value & mask)];
                value >>>= shift;
            }
        } else {
            for (int i = width - 1; i >= 0; i--) {
                result[i] = charset[(int)(value % base)];
                value /= base;
            }
        }
        return new String(result);
    }

    private static boolean isPowerOfTwo(long n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

}
