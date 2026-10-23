package io.pockethive.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        assertThat(value.canManageDeployment()).isFalse();
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
        assertThat(value.canManageUsers() || value.canRunPocketHive() || value.canAccessPocketHive() || value.canManageDeployment()).isFalse();
    }
    @ParameterizedTest
    @CsvSource({"VIEW,false", "RUN,false", "ALL,true"})
    void deploymentManagementRequiresGlobalAll(String permission, boolean manage) {
        var value = projection.project(user(new AuthGrantDto(AuthProduct.POCKETHIVE, permission,
            PocketHiveResourceTypes.DEPLOYMENT, PocketHiveResourceSelectors.GLOBAL)));
        assertThat(value.canManageDeployment()).isEqualTo(manage);
    }
    @ParameterizedTest
    @CsvSource({"auth-access-deployment-manager.json,true", "auth-access-folder-manager.json,false"})
    void serializesTheProducerOwnedUiFixture(String resource, boolean deploymentManager) throws Exception {
        var grant = new AuthGrantDto(AuthProduct.POCKETHIVE, PocketHivePermissionIds.ALL,
            deploymentManager ? PocketHiveResourceTypes.DEPLOYMENT : PocketHiveResourceTypes.FOLDER,
            deploymentManager ? PocketHiveResourceSelectors.GLOBAL : "team");
        var mapper = new ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/" + resource)) {
            assertThat(fixture).isNotNull();
            com.fasterxml.jackson.databind.JsonNode actual = mapper.valueToTree(projection.project(user(grant)));
            assertThat(actual).isEqualTo(mapper.readTree(fixture));
        }
    }
    private AuthenticatedUserDto user(AuthGrantDto... grants) {
        return new AuthenticatedUserDto(UUID.randomUUID(), "tester", "Tester", true, AuthProvider.DEV, List.of(grants));
    }
}
