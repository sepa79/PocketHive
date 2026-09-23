package io.pockethive.redis.api;

/**
 * Responsibility: parse sequence format tokens and calculate their capacity.
 * Must not: access Redis or resolve connection settings.
 * Contract: RESP-TEMPLATE-SEQUENCE — docs/architecture/runtime-responsibilities.md#resp-template-sequence.
 */
record SequencePattern(int capacity, SequenceToken[] tokens) {

    long calculateMax(SequenceMode mode) {
        long total = 1;
        for (SequenceToken token : tokens) {
            if (token.literal() == null) {
                total *= token.mod(mode);
            }
        }
        return total;
    }

    static SequencePattern parse(String format) {
        var tokenList = new java.util.ArrayList<SequenceToken>();
        int cap = 0;
        int i = 0;
        boolean hasSequenceToken = false;

        while (i < format.length()) {
            if (format.charAt(i) != '%') {
                int next = format.indexOf('%', i);
                int end = next == -1 ? format.length() : next;
                String literal = format.substring(i, end);
                tokenList.add(new SequenceToken('\0', 0, false, literal));
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
            tokenList.add(new SequenceToken(type, count, zeroPad, null));
            cap += count;
            hasSequenceToken = true;
        }

        if (!hasSequenceToken) {
            throw new IllegalArgumentException("Format must contain at least one valid token (%S, %s, %d)");
        }

        return new SequencePattern(cap, tokenList.toArray(new SequenceToken[0]));
    }
}
