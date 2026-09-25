package io.pockethive.scenarios;

import io.pockethive.auth.contract.*;
import io.pockethive.scenarios.auth.ScenarioManagerAuthorization;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ScenarioOperationAccessTest {
    private final ScenarioOperationAccess access = new ScenarioOperationAccess(new ScenarioManagerAuthorization(),
        new ScenarioBundleOrganizationService(mock(ScenarioService.class)));
    @Test void uploadFolderGrantDoesNotAllowReload() {
        var user = user(PocketHivePermissionIds.ALL, PocketHiveResourceTypes.FOLDER, "bundles");
        assertThat(access.canUpload(user)).isTrue();
        assertThat(access.canReload(user)).isFalse();
    }
    @Test void unrelatedFolderAllDoesNotAllowEitherOperation() {
        var user = user(PocketHivePermissionIds.ALL, PocketHiveResourceTypes.FOLDER, "team");
        assertThat(access.canUpload(user)).isFalse();
        assertThat(access.canReload(user)).isFalse();
    }
    @Test void globalAllAllowsBoth() {
        var user = user(PocketHivePermissionIds.ALL, PocketHiveResourceTypes.DEPLOYMENT, "*");
        assertThat(access.canUpload(user)).isTrue();
        assertThat(access.canReload(user)).isTrue();
    }
    @Test void runDoesNotGrantManagement() {
        var user = user(PocketHivePermissionIds.RUN, PocketHiveResourceTypes.DEPLOYMENT, "*");
        assertThat(access.canUpload(user)).isFalse();
        assertThat(access.canReload(user)).isFalse();
    }
    @Test void nullCallerRetainsExistingPolicy() {
        assertThat(access.canUpload(null)).isTrue();
        assertThat(access.canReload(null)).isTrue();
    }
    private AuthenticatedUserDto user(String permission, String type, String selector) {
        return new AuthenticatedUserDto(UUID.randomUUID(), "tester", "Tester", true, AuthProvider.DEV,
            List.of(new AuthGrantDto(AuthProduct.POCKETHIVE, permission, type, selector)));
    }
}
