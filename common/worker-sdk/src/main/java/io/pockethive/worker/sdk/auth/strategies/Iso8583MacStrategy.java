package io.pockethive.worker.sdk.auth.strategies;

import io.pockethive.worker.sdk.auth.AuthConfig;
import io.pockethive.worker.sdk.auth.AuthStrategy;
import io.pockethive.worker.sdk.auth.TokenInfo;
import io.pockethive.worker.sdk.api.WorkItem;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * ISO-8583 MAC (Message Authentication Code) for financial transactions.
 * Supports DES/3DES MAC calculation for ATM, POS, and card processing systems.
 */
public class Iso8583MacStrategy implements AuthStrategy {
    
    @Override
    public TokenInfo refresh(AuthConfig config) {
        // MAC keys don't expire
        return new TokenInfo(
            config.properties().get("macKey"),
            Instant.now().getEpochSecond() + 86400,
            Instant.now().getEpochSecond() + 86400,
            config.type(),
            config.properties()
        );
    }
    
    @Override
    public Map<String, String> generateHeaders(AuthConfig config, TokenInfo token, WorkItem item) {
        String macKey = config.properties().get("macKey");
        String algorithm = config.properties().getOrDefault("algorithm", "DES");
        String payload = item.asString();
        
        String mac = calculateMac(payload, macKey, algorithm);
        
        return Map.of(
            "X-ISO8583-MAC", mac,
            "X-MAC-Algorithm", algorithm
        );
    }
    
    private String calculateMac(String data, String keyHex, String algorithm) {
        try {
            byte[] key = HexFormat.of().parseHex(keyHex);
            byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
            
            if ("3DES".equalsIgnoreCase(algorithm) || "TDES".equalsIgnoreCase(algorithm)) {
                return calculate3DesMac(dataBytes, key);
            } else {
                return calculateDesMac(dataBytes, key);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate ISO-8583 MAC", e);
        }
    }
    
    private String calculateDesMac(byte[] data, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(key, "DES");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        
        byte[] padded = padData(data, 8);
        byte[] result = new byte[8];
        
        for (int i = 0; i < padded.length; i += 8) {
            byte[] block = new byte[8];
            System.arraycopy(padded, i, block, 0, 8);
            
            for (int j = 0; j < 8; j++) {
                result[j] ^= block[j];
            }
            result = cipher.doFinal(result);
        }
        
        return HexFormat.of().formatHex(result);
    }
    
    private String calculate3DesMac(byte[] data, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(key, "DESede");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        
        byte[] padded = padData(data, 8);
        byte[] result = new byte[8];
        
        for (int i = 0; i < padded.length; i += 8) {
            byte[] block = new byte[8];
            System.arraycopy(padded, i, block, 0, 8);
            
            for (int j = 0; j < 8; j++) {
                result[j] ^= block[j];
            }
            result = cipher.doFinal(result);
        }
        
        return HexFormat.of().formatHex(result);
    }
    
    private byte[] padData(byte[] data, int blockSize) {
        int paddedLength = ((data.length + blockSize - 1) / blockSize) * blockSize;
        byte[] padded = new byte[paddedLength];
        System.arraycopy(data, 0, padded, 0, data.length);
        // ISO 9797-1 padding method 2 (pad with zeros)
        return padded;
    }
}
