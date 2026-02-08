package io.pockethive.worker.sdk.templating;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.sync.RedisCommands;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Redis-backed sequence generator for unique alphanumeric/binary sequences.
 * Uses printf-style format strings (e.g., "%4S%2d") to generate deterministic sequences.
 */
public final class RedisSequenceGenerator {

    private static final ConcurrentHashMap<String, RedisSequenceGenerator> INSTANCES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> MAX_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ParsedFormat> FORMAT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, SequenceMode> MODE_CACHE = new ConcurrentHashMap<>();
    private static final String KEY_PREFIX = "ph:seq:";
    private static final long[] POW10 = {1,10,100,1000,10000,100000,1000000,10000000,100000000,1000000000,
        10000000000L,100000000000L,1000000000000L,10000000000000L,100000000000000L,1000000000000000L,
        10000000000000000L,100000000000000000L,1000000000000000000L};
    private static final long[] POW26 = precompute(26, 13);
    private static final long[] POW36 = precompute(36, 12);
    private static final AtomicReference<ConnectionConfig> CONFIG = new AtomicReference<>();

    static {
        RedisSequenceProperties defaults = new RedisSequenceProperties();
        CONFIG.set(new ConnectionConfig(
            defaults.getHost(),
            defaults.getPort(),
            defaults.getUsername(),
            defaults.getPassword(),
            defaults.isSsl()
        ));
    }

    public record ConnectionConfig(String host, int port, String username, String password, boolean ssl) {
        String cacheKey() {
            String hostValue = host == null ? "" : host;
            int authHash = Objects.hash(username, password);
            return hostValue + ":" + port + "|ssl=" + ssl + "|auth=" + Integer.toHexString(authHash);
        }
    }

    private final RedisClient client;
    private final ThreadLocal<RedisCommands<String, String>> commandsThreadLocal;

    private RedisSequenceGenerator(ConnectionConfig config) {
        RedisURI.Builder builder = RedisURI.builder()
            .withHost(config.host())
            .withPort(config.port())
            .withSsl(config.ssl());
        if (config.username() != null && config.password() != null) {
            builder.withAuthentication(config.username(), config.password().toCharArray());
        } else if (config.password() != null) {
            builder.withPassword(config.password().toCharArray());
        }
        RedisURI uri = builder.build();
        this.client = RedisClient.create(uri);
        this.commandsThreadLocal = ThreadLocal.withInitial(() -> client.connect().sync());
    }

    public static RedisSequenceGenerator getInstance(String host, int port) {
        return getInstance(new ConnectionConfig(host, port, null, null, false));
    }

    public static RedisSequenceGenerator getInstance(ConnectionConfig config) {
        return INSTANCES.computeIfAbsent(config.cacheKey(), k -> new RedisSequenceGenerator(config));
    }

    public static RedisSequenceGenerator getDefaultInstance() {
        return getInstance(CONFIG.get());
    }

    public static ConnectionConfig currentConfig() {
        return CONFIG.get();
    }

    public static void configure(String host, int port) {
        ConnectionConfig current = CONFIG.get();
        configure(host, port, current.username(), current.password(), current.ssl());
    }

    public static void configure(String host, int port, String username, String password, boolean ssl) {
        CONFIG.set(new ConnectionConfig(host, port, username, password, ssl));
    }

    public String next(String key, String mode, String format, long startOffset, long maxSequence) {
        SequenceMode seqMode = MODE_CACHE.computeIfAbsent(mode, SequenceMode::parse);
        ParsedFormat parsed = FORMAT_CACHE.computeIfAbsent(format, ParsedFormat::parse);

        long value = commandsThreadLocal.get().incr(KEY_PREFIX + key);
        long adjusted = value + startOffset - 1;
        String cacheKey = (mode + ":" + format).intern();
        long max = maxSequence > 0 ? maxSequence : MAX_CACHE.computeIfAbsent(
            cacheKey,
            k -> parsed.calculateMax(seqMode)
        );

        if (max > 0) {
            adjusted = isPowerOfTwo(max)
                ? ((adjusted - 1) & (max - 1)) + 1
                : ((adjusted - 1) % max) + 1;
        }

        return formatSequence(adjusted, seqMode, parsed);
    }

    public boolean reset(String key) {
        return commandsThreadLocal.get().del(KEY_PREFIX + key) > 0;
    }

    public void close() {
        commandsThreadLocal.remove();
        if (client != null) client.shutdown();
    }

    private static String formatSequence(long value, SequenceMode mode, ParsedFormat parsed) {
        StringBuilder result = new StringBuilder(parsed.capacity);
        long seq = value - 1;
        long[] segments = new long[parsed.tokens.length];
        // Assign sequence segments from rightmost token to leftmost (odometer behavior).
        for (int i = parsed.tokens.length - 1; i >= 0; i--) {
            ParsedFormat.Token token = parsed.tokens[i];
            if (token.literal != null) {
                continue;
            }
            long mod = token.mod(mode);
            long segment = seq % mod;
            seq /= mod;
            segments[i] = segment;
        }

        for (int i = 0; i < parsed.tokens.length; i++) {
            ParsedFormat.Token token = parsed.tokens[i];
            if (token.literal != null) {
                result.append(token.literal);
                continue;
            }
            long segment = segments[i];
            switch (token.type) {
                case 'S' -> result.append(encode(segment, mode.upperChars, token.width));
                case 's' -> result.append(encode(segment, mode.lowerChars, token.width));
                case 'd' -> {
                    if (token.zeroPad) {
                        appendZeroPadded(result, segment, token.width);
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

    private static boolean isPowerOfTwo(long n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    private record ParsedFormat(int capacity, Token[] tokens) {
        record Token(char type, int width, boolean zeroPad, String literal) {
            long mod(SequenceMode mode) {
                if (literal != null) {
                    return 1L;
                }
                int base = type == 'd' ? 10 : mode.base;
                return fastPow(base, width);
            }
        }

        long calculateMax(SequenceMode mode) {
            long total = 1;
            for (Token token : tokens) {
                if (token.literal == null) {
                    total *= token.mod(mode);
                }
            }
            return total;
        }

        static ParsedFormat parse(String format) {
            var tokenList = new java.util.ArrayList<Token>();
            int cap = 0;
            int i = 0;
            boolean hasToken = false;

            while (i < format.length()) {
                if (format.charAt(i) != '%') {
                    int next = format.indexOf('%', i);
                    int end = next == -1 ? format.length() : next;
                    String literal = format.substring(i, end);
                    tokenList.add(new Token('\0', 0, false, literal));
                    cap += literal.length();
                    i = end;
                    continue;
                }

                i++;
                boolean zeroPad = i < format.length() && format.charAt(i) == '0';
                if (zeroPad) i++;

                int width = 0;
                while (i < format.length() && Character.isDigit(format.charAt(i))) {
                    width = width * 10 + (format.charAt(i++) - '0');
                }
                if (i >= format.length()) break;

                char type = format.charAt(i++);
                int count = width > 0 ? width : 1;
                tokenList.add(new Token(type, count, zeroPad, null));
                cap += count;
                hasToken = true;
            }

            if (!hasToken) {
                throw new IllegalArgumentException("Format must contain at least one valid token (%S, %s, %d)");
            }

            return new ParsedFormat(cap, tokenList.toArray(new Token[0]));
        }
    }

    enum SequenceMode {
        ALPHA(26, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "abcdefghijklmnopqrstuvwxyz"),
        ALPHA_LOWER(26, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "abcdefghijklmnopqrstuvwxyz"),
        NUMERIC(10, "0123456789", "0123456789"),
        ALPHANUM(36, "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", "abcdefghijklmnopqrstuvwxyz0123456789"),
        ALPHANUM_LOWER(36, "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", "abcdefghijklmnopqrstuvwxyz0123456789"),
        BINARY(2, "01", "01"),
        HEX(16, "0123456789ABCDEF", "0123456789abcdef"),
        HEX_LOWER(16, "0123456789ABCDEF", "0123456789abcdef");

        final int base;
        final char[] upperChars;
        final char[] lowerChars;

        SequenceMode(int base, String upper, String lower) {
            this.base = base;
            this.upperChars = upper.toCharArray();
            this.lowerChars = lower.toCharArray();
        }

        static SequenceMode parse(String mode) {
            try {
                return valueOf(mode.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid mode: " + mode);
            }
        }
    }
}
