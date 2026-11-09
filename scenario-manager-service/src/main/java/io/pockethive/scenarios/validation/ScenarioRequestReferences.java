package io.pockethive.scenarios.validation;

import io.pockethive.scenarios.Scenario;
import io.pockethive.scenarios.ScenarioBundleLayout;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.swarm.model.OutcomeHeaders;
import io.pockethive.swarm.model.ScenarioConfigurationPreparation;
import io.pockethive.templating.TemplateSourceInspector;
import io.pockethive.templating.api.TemplateRenderingException;
import io.pockethive.templating.sources.GeneratorTemplateSources;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Responsibility: project static targets from actual HTTP Sequence steps and connected generator Work headers.
 * Must not: infer selectors from arbitrary maps, evaluate dynamic expressions or select request files.
 * Contract: RESP-SCENARIO-VALIDATE — docs/architecture/runtime-responsibilities.md#resp-scenario-validate.
 */
final class ScenarioRequestReferences {
    private final RequestTemplateFindings steps;
    ScenarioRequestReferences(RequestTemplateFindings steps) { this.steps = steps; }

    Set<ScenarioTemplateReference> collect(Scenario scenario, Bee consumer, Map<String, Object> config,
                                            String defaultServiceId, Function<String, String> workerRole,
                                            List<ValidationFinding> findings) {
        Set<ScenarioTemplateReference> references = new LinkedHashSet<>();
        String path = ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE + ":template.bees." + consumer.role() + ".config";
        if (BeeRoles.HTTP_SEQUENCE.equals(workerRole.apply(consumer.image()))) {
            if (config.get("steps") instanceof List<?> values) {
                for (int i = 0; i < values.size(); i++) if (values.get(i) instanceof Map<?, ?> step) {
                    String call = steps.stepCallId(step);
                    String service = steps.stepServiceId(step);
                    String selectedService = service == null ? defaultServiceId : service;
                    if (call == null || selectedService == null) continue;
                    String stepPath = path + ".steps[" + i + "]";
                    var variables = ScenarioConfigurationPreparation.variableNames(
                        Map.of("call", call, "service", selectedService));
                    if (variables.isEmpty()) references.add(new ScenarioTemplateReference(selectedService, call, stepPath));
                    else findings.add(ValidationIssue.WORK_CONFIGURATION_DEFERRED.finding(ValidationSeverity.WARNING,
                        stepPath, "Request target validation is deferred until configuration variables are resolved."));
                }
            }
            return references;
        }
        if (scenario == null || scenario.getTemplate() == null || scenario.getTemplate().bees() == null) return references;
        Set<String> inputQueues = defaultInputQueues(consumer);
        for (Bee producer : scenario.getTemplate().bees()) {
            if (producer == null || !BeeRoles.GENERATOR.equals(workerRole.apply(producer.image()))
                || !publishesToAny(producer, inputQueues) || producer.config() == null) continue;
            if (!(producer.config().get("message") instanceof Map<?, ?> fields)) continue;
            String producerPath = ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE + ":template.bees." + producer.role() + ".config.message";
            try {
                if (!GeneratorTemplateSources.selectsWorkHeaders(fields)) continue;
                Map<String, String> headers = GeneratorTemplateSources.from(fields).headers();
                if (headers == null) continue;
                String service = headers.get(OutcomeHeaders.SERVICE_ID);
                add(references, service == null || service.isBlank() ? defaultServiceId : service,
                    headers.get(OutcomeHeaders.CALL_ID), producerPath + ".headers", findings);
            } catch (IllegalArgumentException failure) {
                deferred(producerPath, findings);
            }
        }
        return references;
    }

    private void add(Set<ScenarioTemplateReference> references, String service, String call, String path,
                     List<ValidationFinding> findings) {
        if (service == null || call == null) return;
        try {
            String literalService = TemplateSourceInspector.inspect(service).literal();
            String literalCall = TemplateSourceInspector.inspect(call).literal();
            if (literalService == null || literalCall == null) {
                deferred(path, findings);
            } else if (!literalService.isBlank() && !literalCall.isBlank()) {
                references.add(new ScenarioTemplateReference(literalService.trim(), literalCall.trim(), path));
            }
        } catch (TemplateRenderingException ignored) {
            // The source's compiler emits the syntax finding.
        }
    }

    private void deferred(String path, List<ValidationFinding> findings) {
        findings.add(ValidationIssue.WORK_CONFIGURATION_DEFERRED.finding(ValidationSeverity.WARNING, path,
            "Request target validation is deferred until the selector is rendered."));
    }

    private Set<String> defaultInputQueues(Bee bee) {
        if (bee == null || bee.work() == null || bee.work().in() == null || bee.work().in().isEmpty()) {
            return Set.of();
        }
        Set<String> queues = new LinkedHashSet<>();
        bee.work().in().values().forEach(queue -> {
            if (queue != null && !queue.isBlank()) {
                queues.add(queue.trim());
            }
        });
        return Set.copyOf(queues);
    }

    private boolean publishesToAny(Bee bee, Set<String> targetQueues) {
        if (bee == null || bee.work() == null || bee.work().out() == null || bee.work().out().isEmpty()) {
            return false;
        }
        for (String output : bee.work().out().values()) {
            if (output != null && targetQueues.contains(output.trim())) {
                return true;
            }
        }
        return false;
    }

}
