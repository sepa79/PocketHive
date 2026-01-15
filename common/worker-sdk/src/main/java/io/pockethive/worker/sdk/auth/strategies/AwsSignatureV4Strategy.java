package io.pockethive.worker.sdk.auth.strategies;

import io.pockethive.worker.sdk.auth.AuthConfig;
import io.pockethive.worker.sdk.auth.AuthStrategy;
import io.pockethive.worker.sdk.auth.TokenInfo;
import io.pockethive.worker.sdk.api.WorkItem;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

public class AwsSignatureV4Strategy implements AuthStrategy {
    
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    
    @Override
    public TokenInfo refresh(AuthConfig config) {
        // AWS credentials don't expire in the traditional sense
        return new TokenInfo(
            config.properties().get("accessKeyId"),
            Instant.now().getEpochSecond() + 86400,
            Instant.now().getEpochSecond() + 86400,
            config.type(),
            config.properties()
        );
    }
    
    @Override
    public Map<String, String> generateHeaders(AuthConfig config, TokenInfo token, WorkItem item) {
        String accessKeyId = config.properties().get("accessKeyId");
        String secretAccessKey = config.properties().get("secretAccessKey");
        String region = config.properties().get("region");
        String service = config.properties().get("service");
        String method = config.properties().getOrDefault("method", "POST");
        String uri = config.properties().getOrDefault("uri", "/");
        String payload = item.asString();
        
        Instant now = Instant.now();
        String dateStamp = DATE_FORMATTER.format(now);
        String timestamp = TIMESTAMP_FORMATTER.format(now);
        
        String payloadHash = sha256Hex(payload);
        String canonicalRequest = buildCanonicalRequest(method, uri, "", "", payloadHash);
        String credentialScope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n" + timestamp + "\n" + credentialScope + "\n" + sha256Hex(canonicalRequest);
        
        byte[] signingKey = getSignatureKey(secretAccessKey, dateStamp, region, service);
        String signature = HexFormat.of().formatHex(hmacSha256(signingKey, stringToSign));
        
        String authorization = ALGORITHM + " Credential=" + accessKeyId + "/" + credentialScope +
            ", SignedHeaders=host;x-amz-date, Signature=" + signature;
        
        return Map.of(
            "Authorization", authorization,
            "X-Amz-Date", timestamp,
            "X-Amz-Content-Sha256", payloadHash
        );
    }
    
    private String buildCanonicalRequest(String method, String uri, String queryString, String headers, String payloadHash) {
        return method + "\n" + uri + "\n" + queryString + "\n" + headers + "\n\nhost;x-amz-date\n" + payloadHash;
    }
    
    private byte[] getSignatureKey(String key, String dateStamp, String region, String service) {
        byte[] kDate = hmacSha256(("AWS4" + key).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }
    
    private byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }
    
    private String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute SHA-256", e);
        }
    }
}
