package io.pockethive.tcpmock.service;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Service
public class RequestVerificationService {

    private final Map<String, AtomicInteger> patternCounts = new ConcurrentHashMap<>();
    private final Map<String, Expectation> expectations = new ConcurrentHashMap<>();
    private final List<String> allRequests = Collections.synchronizedList(new ArrayList<>());

    public void recordRequest(String message) {
        allRequests.add(message);

        // Update pattern counts
        expectations.keySet().forEach(pattern -> {
            if (Pattern.matches(pattern, message)) {
                patternCounts.computeIfAbsent(pattern, k -> new AtomicInteger(0)).incrementAndGet();
            }
        });
    }

    public void addExpectation(String pattern, String countType, int expectedCount) {
        expectations.put(pattern, new Expectation(pattern, countType, expectedCount));
        patternCounts.putIfAbsent(pattern, new AtomicInteger(0));
    }

    public List<Map<String, Object>> getVerificationResults() {
        List<Map<String, Object>> results = new ArrayList<>();

        expectations.values().forEach(expectation -> {
            int actualCount = patternCounts.getOrDefault(expectation.pattern, new AtomicInteger(0)).get();
            boolean passed = checkExpectation(expectation, actualCount);

            results.add(Map.of(
                "pattern", expectation.pattern,
                "expected", expectation.countType + " " + expectation.expectedCount,
                "actual", actualCount,
                "passed", passed,
                "message", formatMessage(expectation, actualCount, passed)
            ));
        });

        return results;
    }

    public Map<String, Object> getVerificationSummary() {
        List<Map<String, Object>> results = getVerificationResults();
        long passed = results.stream().mapToLong(r -> (Boolean) r.get("passed") ? 1 : 0).sum();
        long failed = results.size() - passed;

        return Map.of(
            "totalExpectations", results.size(),
            "passed", passed,
            "failed", failed,
            "totalRequests", allRequests.size(),
            "results", results
        );
    }

    private boolean checkExpectation(Expectation expectation, int actualCount) {
        switch (expectation.countType) {
            case "exactly": return actualCount == expectation.expectedCount;
            case "atLeast": return actualCount >= expectation.expectedCount;
            case "atMost": return actualCount <= expectation.expectedCount;
            default: return false;
        }
    }

    private String formatMessage(Expectation expectation, int actualCount, boolean passed) {
        String status = passed ? "✓" : "✗";
        return String.format("%s Pattern '%s': %d received (expected: %s %d)",
            status, expectation.pattern, actualCount, expectation.countType, expectation.expectedCount);
    }

    public void reset() {
        patternCounts.clear();
        expectations.clear();
        allRequests.clear();
    }

    private static class Expectation {
        final String pattern;
        final String countType;
        final int expectedCount;

        Expectation(String pattern, String countType, int expectedCount) {
            this.pattern = pattern;
            this.countType = countType;
            this.expectedCount = expectedCount;
        }
    }
}
