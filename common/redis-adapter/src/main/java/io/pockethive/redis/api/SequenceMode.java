package io.pockethive.redis.api;

/**
 * Responsibility: decode the supported sequence alphabets.
 * Must not: access Redis or resolve connection settings.
 * Contract: RESP-TEMPLATE-SEQUENCE — docs/architecture/runtime-responsibilities.md#resp-template-sequence.
 */
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
