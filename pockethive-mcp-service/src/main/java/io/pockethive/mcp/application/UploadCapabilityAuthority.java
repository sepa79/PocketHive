package io.pockethive.mcp.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Issues opaque upload-only credentials and verifies them without retaining their value. */
@Component
public final class UploadCapabilityAuthority {
    private static final int ENTROPY_BYTES = 32;
    private static final Pattern VALUE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern DIGEST_PATTERN = Pattern.compile("sha256:[0-9a-f]{64}");

    private final SecureRandom random;

    public UploadCapabilityAuthority() {
        this(new SecureRandom());
    }

    UploadCapabilityAuthority(SecureRandom random) {
        this.random = java.util.Objects.requireNonNull(random);
    }

    public IssuedUploadCapability issue() {
        byte[] entropy = new byte[ENTROPY_BYTES];
        random.nextBytes(entropy);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        return new IssuedUploadCapability(value, digest(value, "SHA-256"));
    }

    public boolean matches(String presented, String expectedDigest) {
        if (presented == null || !VALUE_PATTERN.matcher(presented).matches()
            || expectedDigest == null || !DIGEST_PATTERN.matcher(expectedDigest).matches()) {
            return false;
        }
        return MessageDigest.isEqual(
            expectedDigest.getBytes(StandardCharsets.US_ASCII),
            digest(presented, "SHA-256").getBytes(StandardCharsets.US_ASCII));
    }

    static String requireDigest(String digest) {
        if (digest == null || !DIGEST_PATTERN.matcher(digest).matches()) {
            throw new IllegalArgumentException("UPLOAD_CAPABILITY_DIGEST_INVALID");
        }
        return digest;
    }

    static String digest(String value, String algorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            return "sha256:" + HexFormat.of().formatHex(
                digest.digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
        }
    }
}
