package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.MessageTypeMapping;
import io.pockethive.tcpmock.model.MockState;
import io.pockethive.tcpmock.model.ProcessedResponse;
import io.pockethive.tcpmock.util.PatternCache;
import io.pockethive.tcpmock.util.AdvancedRequestMatcher;
import org.springframework.stereotype.Service;

/**
 * Responsibility: execute requests against the runtime mapping catalogue.
 * Must not: store mappings or implement transport and filesystem effects.
 * Contract: RESP-TCP-MOCK-EXECUTION — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-execution.
 */
@Service
public class MappingExecutor {
    private final MessageTypeRegistry registry;
    private final PatternCache patternCache;
    private final AdvancedRequestMatcher advancedMatcher;
    private final StateManager stateManager;
    private final EnhancedTemplateEngine templateEngine;
    private final RequestVerificationService verificationService;

    public MappingExecutor(MessageTypeRegistry registry, PatternCache patternCache,
                           AdvancedRequestMatcher advancedMatcher, StateManager stateManager,
                           EnhancedTemplateEngine templateEngine, RequestVerificationService verificationService) {
        this.registry = registry;
        this.patternCache = patternCache;
        this.advancedMatcher = advancedMatcher;
        this.stateManager = stateManager;
        this.templateEngine = templateEngine;
        this.verificationService = verificationService;
    }

    public ProcessedResponse processMessage(String message) {

        // Record for verification
        verificationService.recordRequest(message);

        for (MessageTypeMapping mapping : registry.getSortedMappings()) {
            // Check basic pattern match
            boolean patternMatch = patternCache.matches(message, mapping.getRequestPattern());

            // Check advanced matching criteria
            boolean advancedMatch = mapping.getAdvancedMatching() == null ||
                                   advancedMatcher.matches(message, mapping.getAdvancedMatching());

            if (patternMatch && advancedMatch) {
                // Check scenario state if required
                if (mapping.getScenarioName() != null && mapping.getRequiredScenarioState() != null) {
                    // Ensure state exists before checking — initialises to "Started" if first access
                    stateManager.getOrCreateScenarioState(mapping.getScenarioName());
                    if (!stateManager.isInState(mapping.getScenarioName(), mapping.getRequiredScenarioState())) {
                        continue;
                    }
                }

                mapping.incrementMatchCount();

                // Get or create state for template processing
                MockState state = null;
                if (mapping.getScenarioName() != null) {
                    state = stateManager.getOrCreateScenarioState(mapping.getScenarioName());
                }

                // Process template with enhanced engine
                ProcessedResponse response = templateEngine.processTemplate(
                    mapping.getResponseTemplate(),
                    message,
                    state,
                    mapping.getFixedDelayMs()
                );

                // Override delimiter from mapping
                ProcessedResponse finalResponse = new ProcessedResponse(
                    response.getResponse(),
                    mapping.getResponseDelimiter(),
                    response.getDelayMs(),
                    response.getFault(),
                    response.getProxyTarget()
                );

                // Update scenario state if specified
                if (mapping.getScenarioName() != null && mapping.getNewScenarioState() != null) {
                    stateManager.updateScenarioState(mapping.getScenarioName(), mapping.getNewScenarioState());
                }

                return finalResponse;
            }
        }

        return new ProcessedResponse("UNKNOWN_MESSAGE_TYPE", "\n");
    }

}
