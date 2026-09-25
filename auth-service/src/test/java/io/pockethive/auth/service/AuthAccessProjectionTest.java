package io.pockethive.auth.service;

import io.pockethive.auth.contract.*;
import io.pockethive.auth.service.service.AuthAccessProjection;
import io.pockethive.auth.service.support.AuthGrantChecks;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class AuthAccessProjectionTest {
    private final AuthAccessProjection projection = new AuthAccessProjection(new AuthGrantChecks());
    @ParameterizedTest
    @CsvSource({"VIEW,false", "RUN,true", "ALL,true"})
    void scopedPocketHiveGrantsAllowNavigationButDoNotImplyAuthAdministration(String permission, boolean run) {
        var value = projection.project(user(new AuthGrantDto(AuthProduct.POCKETHIVE, permission, PocketHiveResourceTypes.FOLDER, "team")));
        assertThat(value.canAccessPocketHive()).isTrue();
        assertThat(value.canRunPocketHive()).isEqualTo(run);
        assertThat(value.canManageUsers()).isFalse();
    }
    @Test void authOnlyAdminDoesNotRequirePocketHiveGrants() {
        var value = projection.project(user(new AuthGrantDto(AuthProduct.AUTH_SERVICE, AuthServicePermissionIds.ADMIN,
            AuthServiceResourceTypes.GLOBAL, "*")));
        assertThat(value.canManageUsers()).isTrue();
        assertThat(value.canAccessPocketHive()).isFalse();
        assertThat(value.canRunPocketHive()).isFalse();
    }
    @Test void noGrantsMeanNoCapabilities() {
        var value = projection.project(user());
        assertThat(value.canManageUsers() || value.canRunPocketHive() || value.canAccessPocketHive()).isFalse();
    }
    private AuthenticatedUserDto user(AuthGrantDto... grants) {
        return new AuthenticatedUserDto(UUID.randomUUID(), "tester", "Tester", true, AuthProvider.DEV, List.of(grants));
    }
}
