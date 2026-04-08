package io.pockethive.tcpmock.handler;

import io.pockethive.tcpmock.model.MessageTypeMapping;
import io.pockethive.tcpmock.service.MessageTypeRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Per-connection frame decoder that adaptively selects the frame delimiter on every
 * decode pass rather than locking it in once at connection start.
 *
 * <p>On each pass it scans the buffer for every known non-blank requestDelimiter
 * (sorted by mapping priority descending) and picks the one whose match position
 * comes earliest in the buffer. This means a high-priority XML mapping with
 * {@code requestDelimiter="</Document>"} will correctly accumulate a multi-line
 * document even when a low-priority catch-all with {@code \n} is also registered.
 *
 * <p>Falls back to {@code \n} when no mapping declares a requestDelimiter.
 *
 * <p>Not {@code @Sharable} — one instance per channel.
 */
public class MappingAwareFrameDecoder extends ByteToMessageDecoder {

    private static final byte[] NEWLINE = new byte[]{'\n'};

    private final MessageTypeRegistry registry;
    private final int maxFrameBytes;

    public MappingAwareFrameDecoder(MessageTypeRegistry registry, int maxFrameBytes) {
        this.registry = registry;
        this.maxFrameBytes = maxFrameBytes;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (true) {
            // Re-resolve delimiters on every pass so newly loaded mappings take effect
            // and so the correct delimiter is chosen based on what's actually in the buffer.
            List<byte[]> candidates = resolveDelimiters();

            // Find the earliest match among all candidate delimiters
            int bestPos = -1;
            byte[] bestDelim = null;
            for (byte[] delim : candidates) {
                int pos = indexOf(in, delim);
                if (pos >= 0 && (bestPos < 0 || pos < bestPos)) {
                    bestPos = pos;
                    bestDelim = delim;
                }
            }

            if (bestPos < 0) {
                // No delimiter found yet — check frame size limit
                if (in.readableBytes() > maxFrameBytes) {
                    throw new TooLongFrameException(
                        "Frame exceeds maxFrameBytes (" + maxFrameBytes + ")");
                }
                return; // wait for more data
            }

            // Emit frame content without the delimiter
            out.add(in.readRetainedSlice(bestPos));
            in.skipBytes(bestDelim.length);
        }
    }

    /**
     * Returns all distinct non-blank requestDelimiters from enabled mappings,
     * ordered by mapping priority descending, with the newline fallback appended last.
     * Mappings that explicitly set a non-newline requestDelimiter suppress the default
     * newline framing — their delimiter is the only one considered for those mappings.
     * The newline fallback is only included if at least one mapping uses newline framing.
     */
    private List<byte[]> resolveDelimiters() {
        List<MessageTypeMapping> sorted = registry.getSortedMappings();

        // Collect custom (non-newline) delimiters from high-priority mappings
        List<byte[]> custom = sorted.stream()
            .map(MessageTypeMapping::getRequestDelimiter)
            .filter(d -> d != null && !d.isEmpty() && !d.equals("\n") && !d.equals("\\n"))
            .distinct()
            .map(d -> unescape(d).getBytes(StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        if (!custom.isEmpty()) {
            // Custom delimiters present: use them exclusively.
            // The newline fallback is NOT added — it would split multi-line payloads prematurely.
            return custom;
        }

        // No custom delimiters — fall back to newline
        return java.util.List.of(NEWLINE);
    }

    private String unescape(String s) {
        return s.replace("\\r", "\r").replace("\\n", "\n").replace("\\t", "\t");
    }

    /**
     * Searches for {@code needle} inside {@code haystack} ByteBuf.
     * Returns the index of the first byte of the match, or -1 if not found.
     * Does not advance the reader index.
     */
    private int indexOf(ByteBuf haystack, byte[] needle) {
        int haystackLen = haystack.readableBytes();
        int needleLen = needle.length;
        if (haystackLen < needleLen) return -1;

        int base = haystack.readerIndex();
        outer:
        for (int i = 0; i <= haystackLen - needleLen; i++) {
            for (int j = 0; j < needleLen; j++) {
                if (haystack.getByte(base + i + j) != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
