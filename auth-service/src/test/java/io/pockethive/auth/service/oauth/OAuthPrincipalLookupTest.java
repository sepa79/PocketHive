package io.pockethive.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProduct;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.auth.contract.PocketHiveResourceSelectors;
import io.pockethive.auth.contract.PocketHiveResourceTypes;
import io.pockethive.auth.contract.UserUpsertRequestDto;
import io.pockethive.auth.service.config.AuthServiceProperties;
import io.pockethive.auth.service.config.AuthServiceUserProperties;
import io.pockethive.auth.service.service.InMemoryUserStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OAuthPrincipalLookupTest {
    private static final UUID SUBJECT = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
    private static final UUID OTHER = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");
    private final InMemoryUserStore users = users();
    private final OAuthPrincipalLookup principals = new OAuthPrincipalLookup(users);

    @Test
    void keepsTheOriginalSubjectAndReadsCurrentUserStateAfterUsernameReassignment() {
        var expectedGrants = List.of(new AuthGrantDto(AuthProduct.POCKETHIVE, PocketHivePermissionIds.RUN,
            PocketHiveResourceTypes.DEPLOYMENT, PocketHiveResourceSelectors.GLOBAL));
        users.upsertUser(SUBJECT, new UserUpsertRequestDto("renamed", "Original user", true));
        users.upsertUser(OTHER, new UserUpsertRequestDto("shared-name", "New name owner", true));
        users.replaceGrants(SUBJECT, expectedGrants);

        var user = principals.findActive(SUBJECT.toString()).orElseThrow();
        assertThat(user.id()).isEqualTo(SUBJECT);
        assertThat(user.username()).isEqualTo("renamed");
        assertThat(user.grants()).isEqualTo(expectedGrants);
        assertThat(principals.findActive("shared-name")).isEmpty();
    }

    @Test
    void rejectsTheOriginalUserWhenDisabledDespiteTheNewActiveNameOwner() {
        users.upsertUser(SUBJECT, new UserUpsertRequestDto("renamed", "Original user", false));
        users.upsertUser(OTHER, new UserUpsertRequestDto("shared-name", "New name owner", true));
        assertThat(principals.findActive(SUBJECT.toString())).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"shared-name", "1-1-1-1-1", "AAAAAAAA-1111-2222-3333-444444444444",
        " aaaaaaaa-1111-2222-3333-444444444444", "aaaaaaaa-1111-2222-3333-444444444444 ",
        "cccccccc-1111-2222-3333-444444444444"})
    void rejectsMissingMalformedNoncanonicalAndUnknownSubjects(String subject) {
        assertThat(principals.findActive(subject)).isEmpty();
    }

    @Test
    void doesNotMaskUnexpectedStoreErrorsAsMissingUsers() {
        var broken = mock(InMemoryUserStore.class);
        var failure = new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        when(broken.requireById(SUBJECT)).thenThrow(failure);
        assertThatThrownBy(() -> new OAuthPrincipalLookup(broken).findActive(SUBJECT.toString()))
            .isSameAs(failure);
    }

    private static InMemoryUserStore users() {
        var properties = new AuthServiceProperties();
        properties.setUsers(List.of(user(SUBJECT, "shared-name"), user(OTHER, "other")));
        return new InMemoryUserStore(properties);
    }

    private static AuthServiceUserProperties user(UUID id, String username) {
        var user = new AuthServiceUserProperties();
        user.setId(id);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setActive(true);
        user.setGrants(List.of());
        return user;
    }
}
