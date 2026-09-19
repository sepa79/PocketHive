package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class UploadCapabilityAuthorityTest {
    private final UploadCapabilityAuthority authority = new UploadCapabilityAuthority();

    @Test
    void issuesUniqueHighEntropyUrlSafeCapabilitiesAndStoresOnlyTheirDigest() {
        IssuedUploadCapability first = authority.issue();
        IssuedUploadCapability second = authority.issue();

        assertThat(first.value()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(second.value());
        assertThat(first.digest()).matches("sha256:[0-9a-f]{64}")
            .doesNotContain(first.value());
        assertThat(authority.matches(first.value(), first.digest())).isTrue();
        assertThat(authority.matches(second.value(), first.digest())).isFalse();
    }

    @Test
    void rejectsMissingMalformedOrModifiedCapabilities() {
        IssuedUploadCapability issued = authority.issue();

        assertThat(authority.matches(null, issued.digest())).isFalse();
        assertThat(authority.matches(" ", issued.digest())).isFalse();
        assertThat(authority.matches(issued.value() + "a", issued.digest())).isFalse();
        assertThat(authority.matches(issued.value(), "sha256:" + "0".repeat(64))).isFalse();
        assertThat(authority.matches(issued.value(), "invalid-digest")).isFalse();
        assertThatThrownBy(() -> new UploadCapabilityAuthority((SecureRandom) null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IssuedUploadCapability(null, issued.digest()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IssuedUploadCapability("value", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("UPLOAD_CAPABILITY_DIGEST_INVALID");
        assertThatThrownBy(() -> new IssuedUploadCapability("value", "invalid-digest"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("UPLOAD_CAPABILITY_DIGEST_INVALID");
        assertThatThrownBy(() -> new PreparedUpload<ValidationUploadTicket>(null, "capability"))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PreparedUpload<>(
            org.mockito.Mockito.mock(ValidationUploadTicket.class), " "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("UPLOAD_CAPABILITY_REQUIRED");
        assertThatThrownBy(() -> UploadCapabilityAuthority.digest("value", "missing-algorithm"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("SHA-256 is required by Java");
    }
}
