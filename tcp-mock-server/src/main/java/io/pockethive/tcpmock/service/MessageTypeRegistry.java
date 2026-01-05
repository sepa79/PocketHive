package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.MessageTypeMapping;
import io.pockethive.tcpmock.model.MockState;
import io.pockethive.tcpmock.model.ProcessedResponse;
import io.pockethive.tcpmock.util.PatternCache;
import io.pockethive.tcpmock.util.AdvancedRequestMatcher;
import io.pockethive.tcpmock.model.RequestVerification;
import io.pockethive.tcpmock.handler.Iso8583Handler;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MessageTypeRegistry {
    private final ConcurrentHashMap<String, MessageTypeMapping> mappings = new ConcurrentHashMap<>();
    private final AtomicLong requestCounter = new AtomicLong(0);
    private final PatternCache patternCache;
    private final AdvancedRequestMatcher advancedMatcher;
    private final PaymentLogicEngine paymentEngine;
    private final Iso8583Handler iso8583Handler;
    private final StateManager stateManager;
    private final EnhancedTemplateEngine templateEngine;
    private final RequestVerificationService verificationService;

    public MessageTypeRegistry(PatternCache patternCache,
                             AdvancedRequestMatcher advancedMatcher,
                             PaymentLogicEngine paymentEngine,
                             Iso8583Handler iso8583Handler,
                             StateManager stateManager,
                             EnhancedTemplateEngine templateEngine,
                             RequestVerificationService verificationService) {
        this.patternCache = patternCache;
        this.advancedMatcher = advancedMatcher;
        this.paymentEngine = paymentEngine;
        this.iso8583Handler = iso8583Handler;
        this.stateManager = stateManager;
        this.templateEngine = templateEngine;
        this.verificationService = verificationService;
        initializeDefaultMappings();
    }

    public ProcessedResponse processMessage(String message) {
        long requestId = requestCounter.incrementAndGet();

        // Record for verification
        verificationService.recordRequest(message);

        for (MessageTypeMapping mapping : getSortedMappings()) {
            // Check basic pattern match
            boolean patternMatch = patternCache.matches(message, mapping.getRequestPattern());

            // Check advanced matching criteria
            boolean advancedMatch = mapping.getAdvancedMatching() == null ||
                                   advancedMatcher.matches(message, mapping.getAdvancedMatching());

            if (patternMatch && advancedMatch) {
                // Check scenario state if required
                if (mapping.getScenarioName() != null && mapping.getRequiredScenarioState() != null) {
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

    public List<MessageTypeMapping> getSortedMappings() {
        return mappings.values().stream()
            .filter(MessageTypeMapping::isEnabled)
            .sorted((m1, m2) -> Integer.compare(m2.getPriority(), m1.getPriority()))
            .toList();
    }

    public void addMapping(MessageTypeMapping mapping) {
        mappings.put(mapping.getId(), mapping);
    }

    public void removeMapping(String id) {
        mappings.remove(id);
    }

    public Collection<MessageTypeMapping> getAllMappings() {
        return new ArrayList<>(mappings.values());
    }

    private void initializeDefaultMappings() {
        // Echo mapping (high priority)
        MessageTypeMapping echoMapping = new MessageTypeMapping("echo", "^ECHO.*", "{{message}}", "Echo response");
        echoMapping.setPriority(5);
        addMapping(echoMapping);

        // JSON mapping (medium priority)
        MessageTypeMapping jsonMapping = new MessageTypeMapping("json", "^\\{.*\\}$",
            "{\"status\":\"success\",\"timestamp\":\"{{timestamp}}\",\"echo\":{{message}}}", "JSON response");
        jsonMapping.setPriority(5);
        addMapping(jsonMapping);

        // Default catch-all mapping (lowest priority)
        MessageTypeMapping defaultMapping = new MessageTypeMapping("default", ".*", "OK", "Default response");
        defaultMapping.setPriority(1);
        addMapping(defaultMapping);
    }

    public ScenarioManager getScenarioManager() {
        return stateManager.getScenarioManager();
    }
}
