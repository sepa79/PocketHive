package io.pockethive.processor.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Strategy for reading TCP responses based on behavior type.
 * Extracts common response reading logic from transport implementations.
 */
public interface ResponseReader {
    
    byte[] read(InputStream in, TcpRequest request) throws IOException;
    
    static ResponseReader forBehavior(TcpBehavior behavior) {
        return switch (behavior) {
            case ECHO -> new EchoResponseReader();
            case STREAMING -> new StreamingResponseReader();
            case REQUEST_RESPONSE -> new RequestResponseReader();
            case LENGTH_PREFIX_2B -> new LengthPrefix2BResponseReader();
            case FIRE_FORGET -> (in, req) -> new byte[0];
        };
    }
    
    class EchoResponseReader implements ResponseReader {
        @Override
        public byte[] read(InputStream in, TcpRequest request) throws IOException {
            String endTag = (String) request.options().get("endTag");
            if (endTag != null) {
                return readUntilDelimiter(in, endTag, false);
            }
            
            int expectedBytes = request.payload().length;
            ByteArrayOutputStream baos = new ByteArrayOutputStream(expectedBytes);
            byte[] buffer = new byte[Math.min(1024, expectedBytes)];
            int totalRead = 0;
            int read;
            while (totalRead < expectedBytes && (read = in.read(buffer, 0, Math.min(buffer.length, expectedBytes - totalRead))) != -1) {
                baos.write(buffer, 0, read);
                totalRead += read;
            }
            return baos.toByteArray();
        }
    }
    
    class StreamingResponseReader implements ResponseReader {
        @Override
        public byte[] read(InputStream in, TcpRequest request) throws IOException {
            int maxBytes = (Integer) request.options().getOrDefault("maxBytes", 8192);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int totalRead = 0;
            int read;
            while (totalRead < maxBytes && (read = in.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
                totalRead += read;
            }
            return baos.toByteArray();
        }
    }
    
    class RequestResponseReader implements ResponseReader {
        @Override
        public byte[] read(InputStream in, TcpRequest request) throws IOException {
            String endTag = (String) request.options().get("endTag");
            
            if (endTag != null) {
                return readUntilDelimiter(in, endTag, false);
            }
            
            // No endTag - read until no more data available or maxBytes reached
            int maxBytes = (Integer) request.options().getOrDefault("maxBytes", 8192);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int totalRead = 0;
            int read;
            while (totalRead < maxBytes && (read = in.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
                totalRead += read;
                // Check if we've received data and there's no more immediately available
                if (totalRead > 0 && in.available() == 0) {
                    break;
                }
            }
            return baos.toByteArray();
        }
    }

    class LengthPrefix2BResponseReader implements ResponseReader {
        @Override
        public byte[] read(InputStream in, TcpRequest request) throws IOException {
            int maxBytes = (Integer) request.options().getOrDefault("maxBytes", 8192);
            byte[] lengthBytes = readFully(in, 2);
            int payloadLength = ((lengthBytes[0] & 0xFF) << 8) | (lengthBytes[1] & 0xFF);
            if (payloadLength > maxBytes) {
                throw new IOException("Length-prefixed response exceeds maxBytes: " + payloadLength);
            }
            return readFully(in, payloadLength);
        }
    }
    
    static byte[] readUntilDelimiter(InputStream in, String delimiter, boolean stripDelimiter) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] delimBytes = delimiter.getBytes(StandardCharsets.UTF_8);
        byte[] buffer = new byte[1024];
        int matchPos = 0;
        int read;
        
        while ((read = in.read(buffer)) != -1) {
            for (int i = 0; i < read; i++) {
                byte b = buffer[i];
                baos.write(b);
                if (b == delimBytes[matchPos]) {
                    matchPos++;
                    if (matchPos == delimBytes.length) {
                        byte[] result = baos.toByteArray();
                        return stripDelimiter ? java.util.Arrays.copyOf(result, result.length - delimBytes.length) : result;
                    }
                } else {
                    matchPos = (b == delimBytes[0]) ? 1 : 0;
                }
            }
        }
        return baos.toByteArray();
    }

    static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(bytes, offset, length - offset);
            if (read < 0) {
                throw new IOException("Unexpected end of stream");
            }
            offset += read;
        }
        return bytes;
    }
}
