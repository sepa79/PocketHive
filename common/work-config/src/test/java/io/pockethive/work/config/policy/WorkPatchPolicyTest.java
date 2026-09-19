package io.pockethive.work.config.policy;

import io.pockethive.work.config.WorkInputMutationPolicy;
import io.pockethive.work.config.WorkMutationDescriptors;
import io.pockethive.work.config.WorkMutationPolicyRegistry;
import io.pockethive.work.config.WorkMutationRequest;
import io.pockethive.work.config.WorkOutputMutationPolicy;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkPatchPolicyTest {
    @Test
    void delegatesSelectedInputMutationAndExposesInjectedMetadata() {
        var input = input(WorkerInputType.SCHEDULER, "inputs.scheduler.ratePerSec");
        var policy = policy(input);
        var previous = Map.<String, Object>of(
            "privateConfig", Map.of("token", "secret"),
            "inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1)));

        policy.validate(previous, Map.of("inputs", Map.of("scheduler", Map.of("ratePerSec", 2))), true);

        assertThat(input.requests).singleElement().satisfies(request -> {
            assertThat(request.fieldPath()).isEqualTo("inputs.scheduler.ratePerSec");
            assertThat(request.previousSettings()).containsExactlyEntriesOf(Map.of("ratePerSec", 1));
            assertThat(request.settingsPatch()).containsExactlyEntriesOf(Map.of("ratePerSec", 2));
        });
        assertThat(policy.liveMutableIoPaths()).containsExactly("inputs.scheduler.ratePerSec");
        assertThat(policy.isLiveMutableIoPath("inputs.redis.ratePerSec")).isFalse();
    }

    @Test
    void deliversExplicitNullMutableUpdateToSelectedPolicy() {
        var input = input(WorkerInputType.SCHEDULER, "inputs.scheduler.ratePerSec");
        var patch = new java.util.LinkedHashMap<String, Object>();
        patch.put("ratePerSec", null);
        var previous = Map.<String, Object>of(
            "inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1)));

        policy(input).validate(previous, Map.of("inputs", Map.of("scheduler", patch)), false);

        assertThat(input.requests).singleElement().satisfies(request -> {
            assertThat(request.updatedValue()).isNull();
            assertThat(request.settingsPatch()).containsEntry("ratePerSec", null);
        });
    }

    @Test
    void rejectsOuterTypeAndUnselectedSubblockChanges() {
        var policy = policy(input(WorkerInputType.SCHEDULER, "inputs.scheduler.ratePerSec"));
        var previous = Map.<String, Object>of(
            "inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1)));

        assertThatThrownBy(() -> policy.validate(previous, Map.of("inputs", Map.of("type", "CSV_DATASET")), false))
            .hasMessageContaining("inputs.type");
        assertThatThrownBy(() -> policy.validate(previous, Map.of("inputs", Map.of("csv", Map.of("ratePerSec", 2))), false))
            .hasMessageContaining("inputs.csv.ratePerSec");
    }

    @Test
    void rejectsDisabledOnlyChangeForEnabledWorkerBeforeDelegation() {
        var input = new FakeInput(WorkerInputType.REDIS_DATASET,
            new WorkMutationDescriptors(Set.of("inputs.redis.listName"), Set.of("inputs.redis.listName")));
        var previous = Map.<String, Object>of(
            "inputs", Map.of("type", "REDIS_DATASET", "redis", Map.of("listName", "one")));

        assertThatThrownBy(() -> policy(input).validate(previous,
            Map.of("inputs", Map.of("redis", Map.of("listName", "two"))), true))
            .hasMessageContaining("disabled-only IO field 'inputs.redis.listName'");
        assertThat(input.requests).isEmpty();
    }

    @Test
    void rejectsInputLifecycleControlsDuringBootstrapAndUpdates() {
        var policy = policy(input(WorkerInputType.SCHEDULER));
        var update = Map.<String, Object>of("inputs", Map.of("scheduler", Map.of("enabled", false)));

        assertThatThrownBy(() -> policy.validate(Map.of(), update, false))
            .isInstanceOf(io.pockethive.work.config.WorkConfigurationException.class)
            .hasMessageContaining("inputs.scheduler.enabled");
    }

    @Test
    void registryFailsForMissingAndDuplicatePoliciesWithoutFallback() {
        var missing = new WorkMutationPolicyRegistry(List.of(), List.of());
        assertThatThrownBy(() -> missing.inputPolicy(WorkerInputType.SCHEDULER)).hasMessageContaining("found 0");
        var duplicate = new WorkMutationPolicyRegistry(List.of(input(WorkerInputType.SCHEDULER), input(WorkerInputType.SCHEDULER)), List.of());
        assertThatThrownBy(() -> duplicate.inputPolicy(WorkerInputType.SCHEDULER)).hasMessageContaining("found 2");
    }

    @Test
    void registryProjectsTheComposedAdapterDescriptors() {
        var input = input(WorkerInputType.SCHEDULER, "inputs.scheduler.ratePerSec");
        var output = new FakeOutput(WorkerOutputType.RABBITMQ,
            new WorkMutationDescriptors(Set.of("outputs.rabbit.publisherConfirms"), Set.of()));
        var registry = new WorkMutationPolicyRegistry(List.of(input), List.of(output));

        assertThat(registry.liveMutableIoPaths()).containsExactlyInAnyOrder(
            "inputs.scheduler.ratePerSec", "outputs.rabbit.publisherConfirms");
        assertThat(registry.isLiveMutableIoPath("inputs.scheduler.ratePerSec")).isTrue();
        assertThat(registry.isLiveMutableIoPath("inputs.scheduler.maxMessages")).isFalse();
    }

    @Test
    void requestSnapshotsNestedValuesAndExplicitNulls() {
        var nested = new java.util.LinkedHashMap<String, Object>();
        nested.put("nested", null);
        var request = new WorkMutationRequest("testWorker", Map.of("config", nested), Map.of("config", nested),
            Map.of("config", nested),
            "inputs.scheduler.ratePerSec", 1, null, false);
        nested.put("nested", "changed");
        var expected = new java.util.LinkedHashMap<String, Object>();
        expected.put("nested", null);

        assertThat(request.startupSettings()).containsEntry("config", expected);
        assertThat(request.previousSettings()).containsEntry("config", expected);
        assertThat(request.settingsPatch()).containsEntry("config", expected);
        assertThatThrownBy(() -> request.previousSettings().put("new", "value"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private static FakeInput input(WorkerInputType type, String... mutablePaths) {
        return new FakeInput(type, new WorkMutationDescriptors(Set.of(mutablePaths), Set.of()));
    }

    private static WorkPatchPolicy policy(FakeInput input) {
        return new WorkPatchPolicy("testWorker", input, Map.of(),
            new FakeOutput(WorkerOutputType.NONE, new WorkMutationDescriptors(Set.of(), Set.of())), Map.of());
    }

    private static final class FakeInput implements WorkInputMutationPolicy {
        private final WorkerInputType type;
        private final WorkMutationDescriptors descriptors;
        private final List<WorkMutationRequest> requests = new ArrayList<>();

        private FakeInput(WorkerInputType type, WorkMutationDescriptors descriptors) {
            this.type = type;
            this.descriptors = descriptors;
        }

        @Override
        public WorkerInputType type() {
            return type;
        }

        @Override
        public WorkMutationDescriptors descriptors() {
            return descriptors;
        }

        @Override
        public void validate(WorkMutationRequest request) {
            requests.add(request);
        }
    }

    private record FakeOutput(WorkerOutputType type, WorkMutationDescriptors descriptors)
        implements WorkOutputMutationPolicy {
        @Override
        public void validate(WorkMutationRequest request) {
        }
    }
}
