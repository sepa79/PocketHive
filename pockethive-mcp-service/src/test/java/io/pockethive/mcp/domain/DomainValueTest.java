package io.pockethive.mcp.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mockStatic;

class DomainValueTest {
    @Test
    void principalRequiresAbsoluteIssuerAndSubjectAndNormalisesSubject() {
        PrincipalKey principal = new PrincipalKey(URI.create("https://issuer.example"), " user-1 ");
        assertThat(principal.subject()).isEqualTo("user-1");

        assertThatThrownBy(() -> new PrincipalKey(null, "user"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("issuer must be an absolute URI");
        assertThatThrownBy(() -> new PrincipalKey(URI.create("relative"), "user"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("issuer must be an absolute URI");
        assertThatThrownBy(() -> new PrincipalKey(URI.create("https://issuer.example"), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("subject must not be blank");
        assertThatThrownBy(() -> new PrincipalKey(URI.create("https://issuer.example"), " "))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("subject must not be blank");
    }

    @Test
    void capabilityFingerprintRequiresDigestAndObservationTimeAndNormalisesDigest() {
        Instant observed = Instant.parse("2026-08-18T12:00:00Z");
        CapabilityFingerprint fingerprint = new CapabilityFingerprint(" sha256:value ", observed);
        assertThat(fingerprint.digest()).isEqualTo("sha256:value");
        assertThat(fingerprint.observedAt()).isEqualTo(observed);

        assertThatThrownBy(() -> new CapabilityFingerprint(null, observed))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("digest must not be blank");
        assertThatThrownBy(() -> new CapabilityFingerprint(" ", observed))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("digest must not be blank");
        assertThatThrownBy(() -> new CapabilityFingerprint("sha256:value", null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("observedAt must not be null");
    }

    @Test
    void requirementFactoriesRequireAndNormaliseText() {
        AnswerProvenance provenance = new AnswerProvenance(null, null, null, null, null, 0,
            null, null, ElicitationAction.ACCEPT, null, null);

        assertThat(RequirementAnswer.userProvided(" value ", provenance).value()).isEqualTo("value");
        assertThat(RequirementAnswer.notApplicable(" reason ", provenance).value()).isEqualTo("reason");
        ConfirmedSource source = new ConfirmedSource(" docs/example.yaml ", "sha256:" + "a".repeat(64));
        assertThat(RequirementAnswer.userConfirmedSource(" confirmed ", source, provenance))
            .satisfies(answer -> {
                assertThat(answer.value()).isEqualTo("confirmed");
                assertThat(answer.confirmedSource().name()).isEqualTo("docs/example.yaml");
            });
        assertThat(RequirementAnswer.unknown().disposition()).isEqualTo(RequirementDisposition.UNKNOWN);
        assertThat(RequirementAnswer.unknown().value()).isNull();
        assertThat(RequirementAnswer.unknown().provenance()).isNull();
        assertThatThrownBy(() -> RequirementAnswer.userProvided(null, provenance))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("answer value must not be blank");
        assertThatThrownBy(() -> RequirementAnswer.notApplicable(" ", provenance))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("answer value must not be blank");
        assertThatThrownBy(() -> new ConfirmedSource("source", "sha256:bad"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfirmedSource(null, "sha256:" + "a".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("name must not be blank");
        assertThatThrownBy(() -> new ConfirmedSource(" ", "sha256:" + "a".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("name must not be blank");
        assertThatThrownBy(() -> new RequirementAnswer(
            RequirementDisposition.USER_CONFIRMED_SOURCE, "value", provenance))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequirementAnswer(
            RequirementDisposition.USER_PROVIDED, "value", provenance, source))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("confirmed source is forbidden for this disposition");
    }

    @Test
    void confirmedSourceHasStableRecordValueSemantics() {
        String digest = "sha256:" + "a".repeat(64);
        ConfirmedSource source = new ConfirmedSource(" source.yaml ", digest);

        assertThat(source)
            .isEqualTo(new ConfirmedSource("source.yaml", digest))
            .hasSameHashCodeAs(new ConfirmedSource("source.yaml", digest));
        assertThat(source.name()).isEqualTo("source.yaml");
        assertThat(source.digest()).isEqualTo(digest);
        assertThat(source.toString()).contains("source.yaml", digest);
    }

    @Test
    void sourceAndManifestRejectEveryInvalidBoundary() {
        String sha = "sha256:" + "a".repeat(64);
        BundleFileManifestEntry entry = new BundleFileManifestEntry("scenario.yaml", 1, sha);

        assertThatThrownBy(() -> new SourceMetadata("repo", "short", "bundle", SourceVerification.CLIENT_ASSERTED))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("SOURCE_COMMIT_INVALID");
        for (String path : List.of("/bundle", "folder\\bundle", "folder/../bundle")) {
            assertThatThrownBy(() -> new SourceMetadata(
                "repo", "a".repeat(40), path, SourceVerification.CLIENT_ASSERTED))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("SOURCE_BUNDLE_PATH_INVALID");
        }
        assertThatThrownBy(() -> new SourceMetadata(
            " ", "a".repeat(40), "bundle", SourceVerification.CLIENT_ASSERTED))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("repository must not be blank");
        assertThatThrownBy(() -> new BundleFileManifest(List.of(entry, entry)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("BUNDLE_MANIFEST_PATH_DUPLICATE");
        assertThatThrownBy(() -> new BundleFileManifestEntry("", 0, sha))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("BUNDLE_MANIFEST_ENTRY_INVALID");
        assertThatThrownBy(() -> new BundleFileManifestEntry("file", -1, sha))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("BUNDLE_MANIFEST_ENTRY_INVALID");
        assertThatThrownBy(() -> new BundleFileManifestEntry("file", 0, "sha256:bad"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("BUNDLE_MANIFEST_ENTRY_INVALID");

        assertThat(BundleFileManifestEntry.fromBytes("file", "value".getBytes(StandardCharsets.UTF_8)).sha256())
            .matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void missingRequiredSha256ProviderFailsExplicitly() {
        try (MockedStatic<MessageDigest> digests = mockStatic(MessageDigest.class)) {
            digests.when(() -> MessageDigest.getInstance("SHA-256"))
                .thenThrow(new NoSuchAlgorithmException("missing"));

            assertThatThrownBy(() -> BundleFileManifestEntry.fromBytes("file", new byte[0]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SHA-256 is required by Java");
        }
    }
}
