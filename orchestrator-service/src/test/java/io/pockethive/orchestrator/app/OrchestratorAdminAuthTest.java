package io.pockethive.orchestrator.app;

import io.pockethive.swarm.model.NetworkMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProduct;
import io.pockethive.auth.contract.AuthProvider;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.auth.contract.PocketHiveResourceTypes;
import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import io.pockethive.orchestrator.auth.OrchestratorAuthorization;
import io.pockethive.orchestrator.auth.OrchestratorCurrentUserHolder;
import io.pockethive.orchestrator.auth.OrchestratorEndpointAuthorization;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class OrchestratorAdminAuthTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void controlPlaneRefreshRejectsFolderAdminWithoutDeploymentGrant() {
        SwarmStore store = storeWithSwarm("demo-swarm", "demo/tpl-1", "demo");
        ControlPlaneSyncController controller = new ControlPlaneSyncController(
            mock(ControlPlaneSyncService.class),
            endpointAuthorization(store));

        try {
            OrchestratorCurrentUserHolder.set(userWith(
                PocketHivePermissionIds.ALL,
                PocketHiveResourceTypes.FOLDER,
                "demo"));
            assertThatThrownBy(controller::refresh)
                .hasMessageContaining("403 FORBIDDEN");
        } finally {
            OrchestratorCurrentUserHolder.clear();
        }
    }

    @Test
    void controlPlaneSchemaAllowsReadUser() {
        SwarmStore store = storeWithSwarm("demo-swarm", "demo/tpl-1", "demo");
        ControlPlaneSchemaController controller = new ControlPlaneSchemaController(endpointAuthorization(store));

        try {
            OrchestratorCurrentUserHolder.set(userWith(
                PocketHivePermissionIds.VIEW,
                PocketHiveResourceTypes.FOLDER,
                "demo"));
            assertThat(controller.schema(null).getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            OrchestratorCurrentUserHolder.clear();
        }
    }

    @Test
    void hiveJournalPageRequiresDeploymentReadForGlobalQuery() {
        SwarmStore store = storeWithSwarm("demo-swarm", "demo/tpl-1", "demo");
        JournalController controller = new JournalController(
            mock(JdbcTemplate.class),
            mapper,
            endpointAuthorization(store));
        ReflectionTestUtils.setField(controller, "journalSink", "file");

        try {
            OrchestratorCurrentUserHolder.set(userWith(
                PocketHivePermissionIds.VIEW,
                PocketHiveResourceTypes.FOLDER,
                "demo"));
            assertThatThrownBy(() -> controller.hiveJournalPage(null, null, null, null, null, 10))
                .hasMessageContaining("403 FORBIDDEN");
        } finally {
            OrchestratorCurrentUserHolder.clear();
        }
    }

    @Test
    void swarmJournalRejectsUserOutsideScope() {
        SwarmStore store = storeWithSwarm("prod-swarm", "prod/tpl-1", "prod");
        SwarmJournalController controller = new SwarmJournalController(
            mapper,
            mock(JdbcTemplate.class),
            store,
            endpointAuthorization(store),
            runtimeLayout());
        ReflectionTestUtils.setField(controller, "journalSink", "file");

        try {
            OrchestratorCurrentUserHolder.set(userWith(
                PocketHivePermissionIds.VIEW,
                PocketHiveResourceTypes.FOLDER,
                "demo"));
            assertThatThrownBy(() -> controller.journal("prod-swarm", null, null))
                .hasMessageContaining("403 FORBIDDEN");
        } finally {
            OrchestratorCurrentUserHolder.clear();
        }
    }

    @Test
    void debugTapReadAllowsViewerWithinScope() {
        SwarmStore store = storeWithSwarm("demo-swarm", "demo/tpl-1", "demo");
        DebugTapService service = mock(DebugTapService.class);
        DebugTapController.DebugTapResponse response = new DebugTapController.DebugTapResponse(
            "tap-1",
            "demo-swarm",
            "generator",
            "OUT",
            "out",
            "ph.demo-swarm.hive",
            "ph.demo-swarm.out",
            "ph.debug.demo-swarm.generator.tap-1",
            1,
            60,
            Instant.now(),
            Instant.now(),
            List.of());
        when(service.describe("tap-1")).thenReturn(response);
        when(service.read("tap-1", 1)).thenReturn(response);
        DebugTapController controller = new DebugTapController(service, endpointAuthorization(store));

        try {
            OrchestratorCurrentUserHolder.set(userWith(
                PocketHivePermissionIds.VIEW,
                PocketHiveResourceTypes.FOLDER,
                "demo"));
            assertThat(controller.read("tap-1", 1).getBody()).isEqualTo(response);
        } finally {
            OrchestratorCurrentUserHolder.clear();
        }
    }

    @Test
    void debugTapCloseRejectsViewerWithoutManageScope() {
        SwarmStore store = storeWithSwarm("prod-swarm", "prod/tpl-1", "prod");
        DebugTapService service = mock(DebugTapService.class);
        DebugTapController.DebugTapResponse response = new DebugTapController.DebugTapResponse(
            "tap-1",
            "prod-swarm",
            "generator",
            "OUT",
            "out",
            "ph.prod-swarm.hive",
            "ph.prod-swarm.out",
            "ph.debug.prod-swarm.generator.tap-1",
            1,
            60,
            Instant.now(),
            Instant.now(),
            List.of());
        when(service.describe("tap-1")).thenReturn(response);
        DebugTapController controller = new DebugTapController(service, endpointAuthorization(store));

        try {
            OrchestratorCurrentUserHolder.set(userWith(
                PocketHivePermissionIds.VIEW,
                PocketHiveResourceTypes.FOLDER,
                "demo"));
            assertThatThrownBy(() -> controller.close("tap-1"))
                .hasMessageContaining("403 FORBIDDEN");
        } finally {
            OrchestratorCurrentUserHolder.clear();
        }
    }

    private OrchestratorEndpointAuthorization endpointAuthorization(SwarmStore store) {
        return new OrchestratorEndpointAuthorization(
            new OrchestratorAuthorization(),
            scenarioClient(),
            store);
    }

    private ScenarioClient scenarioClient() {
        return new ScenarioClient() {
            @Override
            public io.pockethive.orchestrator.domain.ScenarioPlan fetchScenario(String templateId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ScenarioTemplateDescriptor fetchScenarioTemplate(String templateId) {
                if (templateId.startsWith("prod")) {
                    return new ScenarioTemplateDescriptor(templateId, "prod/" + templateId, "prod/" + templateId, "prod", false);
                }
                return new ScenarioTemplateDescriptor(templateId, "demo/" + templateId, "demo/" + templateId, "demo", false);
            }

            @Override
            public String prepareScenarioRuntime(String templateId, String swarmId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public io.pockethive.swarm.model.SutEnvironment fetchScenarioSut(String templateId,
                                                                             String sutId,
                                                                             String correlationId,
                                                                             String idempotencyKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ResolvedVariables resolveScenarioVariables(String templateId,
                                                             String profileId,
                                                             String sutId,
                                                             String correlationId,
                                                             String idempotencyKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public io.pockethive.swarm.model.NetworkProfile fetchNetworkProfile(String profileId,
                                                                                String correlationId,
                                                                                String idempotencyKey) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static SwarmStore storeWithSwarm(String swarmId, String bundlePath, String folderPath) {
        SwarmStore store = new SwarmStore();
        Swarm swarm = new Swarm(swarmId, "controller-1", "container-1", "run-1", NetworkMode.DIRECT);
        String templateId = bundlePath.substring(bundlePath.lastIndexOf('/') + 1);
        swarm.attachTemplate(new SwarmTemplateMetadata(templateId, "swarm-controller:latest", List.of(), bundlePath, folderPath));
        store.register(swarm);
        return store;
    }

    private static RuntimeFilesystemLayout runtimeLayout() {
        String root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().toString();
        return RuntimeFilesystemLayout.of(root, root);
    }

    private static AuthenticatedUserDto userWith(String permission, String resourceType, String resourceSelector) {
        return new AuthenticatedUserDto(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "local-user",
            "Local User",
            true,
            AuthProvider.DEV,
            List.of(new AuthGrantDto(
                AuthProduct.POCKETHIVE,
                permission,
                resourceType,
                resourceSelector
            ))
        );
    }
}
