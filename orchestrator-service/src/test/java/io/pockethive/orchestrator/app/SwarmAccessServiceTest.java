package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.pockethive.auth.contract.*;
import io.pockethive.orchestrator.auth.OrchestratorAuthorization;
import io.pockethive.orchestrator.domain.*;
import io.pockethive.swarm.model.NetworkMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SwarmAccessServiceTest {
    private final SwarmStore store = new SwarmStore();
    private final ScenarioClient scenarios = mock(ScenarioClient.class);
    private final SwarmAccessService access = new SwarmAccessService(new OrchestratorAuthorization(),
        new SwarmTemplateScopeResolver(scenarios), store);

    private Swarm swarm(String id, String folder) {
        Swarm swarm = new Swarm(id, "controller", "container", "run", NetworkMode.DIRECT);
        if (folder != null) swarm.attachTemplate(new SwarmTemplateMetadata(id, null, List.of(), folder + "/" + id, folder));
        store.register(swarm);
        return swarm;
    }
    private AuthenticatedUserDto user(String permission, String type, String selector) {
        return new AuthenticatedUserDto(UUID.randomUUID(), "tester", "Tester", true, AuthProvider.DEV,
            List.of(new AuthGrantDto(AuthProduct.POCKETHIVE, permission, type, selector)));
    }
    @Test void filtersByScopeAndPreservesRunWithoutManage() {
        swarm("z", "team/sub"); swarm("hidden", "elsewhere"); swarm("a", "team");
        var user = user(PocketHivePermissionIds.RUN, PocketHiveResourceTypes.FOLDER, "team");
        assertThat(access.project(user).swarms()).containsExactly(
            new SwarmAccessView("a", true, false), new SwarmAccessView("z", true, false));
    }
    @Test void unrelatedAllGrantDoesNotAuthorizeMissingScope() {
        Swarm missing = swarm("missing", null);
        var user = user(PocketHivePermissionIds.ALL, PocketHiveResourceTypes.FOLDER, "team");
        assertThat(access.project(user).swarms()).isEmpty();
        assertThat(access.canRun(user, missing)).isFalse();
        assertThat(access.canManage(user, missing)).isFalse();
    }
    @Test void globalGrantStillAppliesWithoutTemplateMetadata() {
        swarm("missing", null);
        assertThat(access.project(user(PocketHivePermissionIds.ALL,
            PocketHiveResourceTypes.DEPLOYMENT, PocketHiveResourceSelectors.GLOBAL)).swarms())
            .containsExactly(new SwarmAccessView("missing", true, true));
    }
    @Test void viewOnlyGrantDoesNotEnableActions() {
        swarm("one", "team");
        assertThat(access.project(user(PocketHivePermissionIds.VIEW,
            PocketHiveResourceTypes.BUNDLE, "team/one")).swarms())
            .containsExactly(new SwarmAccessView("one", false, false));
    }
    @Test void disabledAuthPreservesAccessWithoutFetchingMissingScope() {
        Swarm swarm = swarm("one", null);
        swarm.attachTemplate(new SwarmTemplateMetadata("one", null, List.of()));
        assertThat(access.project(null).swarms()).containsExactly(new SwarmAccessView("one", true, true));
        verifyNoInteractions(scenarios);
    }
    @Test void commandCheckUsesCurrentGrantInsteadOfAnEarlierProjection() {
        Swarm swarm = swarm("one", "team");
        assertThat(access.project(user(PocketHivePermissionIds.ALL,
            PocketHiveResourceTypes.FOLDER, "team")).swarms().getFirst().canManage()).isTrue();
        assertThat(access.canManage(user(PocketHivePermissionIds.VIEW,
            PocketHiveResourceTypes.FOLDER, "team"), swarm)).isFalse();
    }
    @Test void scopeLookupFailureDoesNotBecomeAnEmptySuccess() {
        Swarm swarm = swarm("one", null);
        swarm.attachTemplate(new SwarmTemplateMetadata("one", null, List.of()));
        var user = user(PocketHivePermissionIds.ALL, PocketHiveResourceTypes.DEPLOYMENT,
            PocketHiveResourceSelectors.GLOBAL);
        assertThatThrownBy(() -> access.project(user)).isInstanceOf(IllegalStateException.class);
    }
}
