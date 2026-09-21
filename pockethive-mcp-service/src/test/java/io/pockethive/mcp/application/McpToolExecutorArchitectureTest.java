package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class McpToolExecutorArchitectureTest {
    @Test
    void facadeDependsOnlyOnBoundedOwnerExecutors() {
        assertThat(Arrays.stream(McpToolExecutor.class.getDeclaredFields())
            .filter(field -> !field.isSynthetic())
            .map(field -> field.getType().getName()))
            .containsExactlyInAnyOrder(
                ScenarioManagerToolExecutor.class.getName(),
                OrchestratorToolExecutor.class.getName(),
                QaWorkflowToolExecutor.class.getName());
    }

    @Test
    void qaDispatcherDependsOnlyOnBoundedWorkflowHandlers() {
        assertThat(Arrays.stream(QaWorkflowToolExecutor.class.getDeclaredFields())
            .filter(field -> !field.isSynthetic())
            .map(field -> field.getType().getName()))
            .containsExactlyInAnyOrder(
                BundleToolExecutor.class.getName(),
                AgentSessionToolExecutor.class.getName(),
                ScenarioWorkflowToolExecutor.class.getName());
    }

    @Test
    void ownerExecutorsAreClosedForInheritance() {
        assertThat(Modifier.isFinal(McpToolExecutor.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(ScenarioManagerToolExecutor.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(OrchestratorToolExecutor.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(QaWorkflowToolExecutor.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(AgentSessionToolExecutor.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(ScenarioWorkflowToolExecutor.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(BundleToolExecutor.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(WorkflowProjection.class.getModifiers())).isTrue();
        assertThat(ToolArguments.values()).isEmpty();
        assertThat(McpToolDefaults.values()).isEmpty();
    }

    @Test
    void everyCatalogueToolUsesTheCanonicalTypedIdentifierAndExactlyOneOwnerHandler() {
        ScenarioManagerToolExecutor scenarios = new ScenarioManagerToolExecutor(mock(OwnerApiPort.class));
        OrchestratorToolExecutor orchestrator = new OrchestratorToolExecutor(
            mock(OwnerApiPort.class), mock(SwarmReadinessObserver.class));

        assertThat(ToolCatalogue.canonical().tools()).allSatisfy(tool -> {
            assertThat(tool.toolId().externalName()).isEqualTo(tool.id());
            assertThat((scenarios.supports(tool.toolId()) ? 1 : 0)
                + (orchestrator.supports(tool.toolId()) ? 1 : 0)
                + (tool.owner() == ToolOwner.MCP ? 1 : 0)).isEqualTo(1);
        });
    }

    @Test
    void slowOwnerCallDoesNotBlockAnUnrelatedToolInvocation() throws Exception {
        CountDownLatch slowCallEntered = new CountDownLatch(1);
        CountDownLatch releaseSlowCall = new CountDownLatch(1);
        ScenarioManagerToolExecutor scenarios = mock(ScenarioManagerToolExecutor.class);
        OrchestratorToolExecutor orchestrator = mock(OrchestratorToolExecutor.class);
        QaWorkflowToolExecutor workflows = mock(QaWorkflowToolExecutor.class);
        McpToolExecutor executor = new McpToolExecutor(scenarios, orchestrator, workflows);
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        org.mockito.Mockito.when(exchange.transportContext()).thenReturn(
            io.modelcontextprotocol.common.McpTransportContext.create(Map.of(
                "pockethive.issuer", "https://issuer.example",
                "pockethive.subject", "qa-lead",
                "pockethive.principalLabel", "QA lead",
                "pockethive.clientId", "test-client",
                "pockethive.scopes", io.pockethive.auth.contract.PocketHiveMcpScopes.READ)));
        org.mockito.Mockito.when(scenarios.execute(McpToolId.SCENARIO_LIST, Map.of())).thenAnswer(ignored -> {
            slowCallEntered.countDown();
            assertThat(releaseSlowCall.await(5, TimeUnit.SECONDS)).isTrue();
            return "slow";
        });
        org.mockito.Mockito.when(orchestrator.execute(McpToolId.SWARM_LIST, Map.of())).thenReturn("fast");

        CompletableFuture<Object> slow = CompletableFuture.supplyAsync(() -> executor.execute(
            ToolCatalogue.canonical().requireTool("scenario_list"), exchange, Map.of()));
        assertThat(slowCallEntered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Object> fast = CompletableFuture.supplyAsync(() -> executor.execute(
            ToolCatalogue.canonical().requireTool("swarm_list"), exchange, Map.of()));

        assertThat(fast.get(1, TimeUnit.SECONDS)).isEqualTo("fast");
        releaseSlowCall.countDown();
        assertThat(slow.get(1, TimeUnit.SECONDS)).isEqualTo("slow");
    }

    @Test
    void boundedHandlersAndDefaultsRejectUnsupportedIdsExplicitly() {
        ScenarioManagerToolExecutor scenarios = new ScenarioManagerToolExecutor(mock(OwnerApiPort.class));
        BundleToolExecutor bundles = new BundleToolExecutor(
            mock(BundleUploadCoordinator.class),
            mock(PocketHiveMcpProperties.class),
            mock(WorkflowAccess.class),
            Clock.systemUTC());
        AgentSessionToolExecutor sessions = new AgentSessionToolExecutor(
            mock(WorkflowAccess.class), mock(PocketHiveMcpProperties.class),
            mock(CoordinationStateRepository.class), new WorkflowProjection(), Clock.systemUTC());
        ScenarioWorkflowToolExecutor workflows = new ScenarioWorkflowToolExecutor(
            mock(WorkflowAccess.class), mock(OwnerApiPort.class), mock(PocketHiveMcpProperties.class),
            mock(ObjectMapper.class), mock(CoordinationStateRepository.class),
            new WorkflowProjection(), Clock.systemUTC());
        OrchestratorToolExecutor orchestrator = new OrchestratorToolExecutor(
            mock(OwnerApiPort.class), mock(SwarmReadinessObserver.class));
        QaWorkflowToolExecutor qa = new QaWorkflowToolExecutor(bundles, sessions, workflows);

        assertThatThrownBy(() -> scenarios.execute(McpToolId.SWARM_LIST, Map.of()))
            .isInstanceOfSatisfying(ToolExecutionException.class,
                exception -> assertThat(exception.code()).isEqualTo("TOOL_HANDLER_MISSING"));
        assertThatThrownBy(() -> bundles.execute(McpToolId.SWARM_LIST, Map.of(), null))
            .isInstanceOfSatisfying(ToolExecutionException.class,
                exception -> assertThat(exception.code()).isEqualTo("TOOL_HANDLER_MISSING"));
        assertThatThrownBy(() -> sessions.execute(McpToolId.SWARM_LIST, null, Map.of()))
            .isInstanceOfSatisfying(ToolExecutionException.class,
                exception -> assertThat(exception.code()).isEqualTo("TOOL_HANDLER_MISSING"));
        assertThat(workflows.supports(McpToolId.SWARM_LIST)).isFalse();
        assertThatThrownBy(() -> workflows.execute(McpToolId.SWARM_LIST, null, null, Map.of()))
            .isInstanceOfSatisfying(ToolExecutionException.class,
                exception -> assertThat(exception.code()).isEqualTo("TOOL_HANDLER_MISSING"));
        assertThatThrownBy(() -> orchestrator.execute(McpToolId.SCENARIO_LIST, Map.of()))
            .isInstanceOfSatisfying(ToolExecutionException.class,
                exception -> assertThat(exception.code()).isEqualTo("TOOL_HANDLER_MISSING"));
        assertThatThrownBy(() -> qa.execute(McpToolId.SWARM_LIST, null, null, Map.of()))
            .isInstanceOfSatisfying(ToolExecutionException.class,
                exception -> assertThat(exception.code()).isEqualTo("TOOL_HANDLER_MISSING"));
        assertThat(ToolCatalogue.canonical().tools().stream()
            .filter(tool -> tool.owner() == ToolOwner.MCP)
            .map(tool -> (bundles.supports(tool.toolId()) ? 1 : 0)
                + (sessions.supports(tool.toolId()) ? 1 : 0)
                + (workflows.supports(tool.toolId()) ? 1 : 0)))
            .allMatch(ownerCount -> ownerCount == 1);
        assertThatThrownBy(() -> McpToolDefaults.requireLimitFor(McpToolId.SWARM_LIST))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("No MCP limit default for swarm_list");
    }
}
