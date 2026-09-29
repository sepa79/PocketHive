package io.pockethive.scenarios;

import io.pockethive.auth.contract.*;
import io.pockethive.scenarios.auth.ScenarioManagerAuthorization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ScenarioAccessServiceTest {
    private final ScenarioService scenarios = mock(ScenarioService.class);
    private final ScenarioManagerAuthorization authorization = new ScenarioManagerAuthorization();
    private final ScenarioBundleService bundles = mock(ScenarioBundleService.class);
    private final ScenarioAccessService access = new ScenarioAccessService(scenarios, authorization, bundles);

    @org.junit.jupiter.api.BeforeEach
    void uploadTarget() {
        when(bundles.uploadFolder()).thenReturn(new ScenarioBundleOrganizationService(scenarios).uploadFolder());
    }

    @ParameterizedTest
    @ValueSource(strings = {PocketHivePermissionIds.VIEW, PocketHivePermissionIds.RUN, PocketHivePermissionIds.ALL})
    void projectsExistingPolicyAndOmitsUnrelatedBundles(String permission) {
        bundle("team/a", "a"); bundle("other/b", "b");
        when(scenarios.listBundleTemplates()).thenReturn(List.of(summary("team/a", "a"), summary("other/b", "b")));
        assertThat(access.bundles(user(permission)).bundles()).containsExactly(
            new BundleAccessView("team/a", permission.equals(PocketHivePermissionIds.ALL)));
    }

    @Test void malformedBundleWithoutIdUsesBundleVisibilityAndCanBeEdited() {
        bundle("team/broken", null);
        when(scenarios.listBundleTemplates()).thenReturn(List.of(summary("team/broken", null)));
        assertThat(access.bundles(user(PocketHivePermissionIds.ALL)).bundles())
            .containsExactly(new BundleAccessView("team/broken", true));
    }

    @Test void duplicateIdDoesNotGrantReadOrEditToAnotherBundle() {
        bundle("team/a", "duplicate"); bundle("other/b", "duplicate");
        when(scenarios.findScenarioAccess("duplicate")).thenReturn(Optional.of(new ScenarioAccessDescriptor("duplicate", "team/a", "team")));
        when(scenarios.listBundleTemplates()).thenReturn(List.of(summary("team/a", "duplicate"), summary("other/b", "duplicate")));
        assertThat(access.bundles(user(PocketHivePermissionIds.ALL)).bundles())
            .containsExactly(new BundleAccessView("team/a", true));
    }

    @Test void missingBundleDescriptorNeverInventsEditPermission() {
        bundle("team/a", "a");
        when(scenarios.findBundleAccess("team/a")).thenReturn(Optional.empty());
        when(scenarios.listBundleTemplates()).thenReturn(List.of(summary("team/a", "a")));
        assertThat(access.bundles(user(PocketHivePermissionIds.ALL)).bundles())
            .isEmpty();
    }

    @Test void retainsAuthDisabledSemantics() {
        bundle("other/b", "b");
        when(scenarios.listBundleTemplates()).thenReturn(List.of(summary("other/b", "b")));
        assertThat(access.bundles(null).bundles()).containsExactly(new BundleAccessView("other/b", true));
    }

    private void bundle(String key, String id) {
        var access = new ScenarioAccessDescriptor(id, key, key.substring(0, key.indexOf('/')));
        when(scenarios.findBundleAccess(key)).thenReturn(Optional.of(access));
        if (id != null) when(scenarios.findScenarioAccess(id)).thenReturn(Optional.of(access));
    }
    private BundleTemplateSummary summary(String key, String id) {
        return new BundleTemplateSummary(key, key, key.substring(0, key.indexOf('/')), id, "test", null, null, List.of(), id == null, null);
    }
    private AuthenticatedUserDto user(String permission) {
        return new AuthenticatedUserDto(UUID.randomUUID(), "tester", "Tester", true, AuthProvider.DEV,
            List.of(new AuthGrantDto(AuthProduct.POCKETHIVE, permission, PocketHiveResourceTypes.FOLDER, "team")));
    }
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
