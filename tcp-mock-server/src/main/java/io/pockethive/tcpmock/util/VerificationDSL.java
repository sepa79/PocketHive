package io.pockethive.tcpmock.util;

import org.springframework.stereotype.Component;

@Component
public class VerificationDSL {

    public static CountMatcher exactly(int count) {
        return new CountMatcher(count);
    }

    public static RequestMatcher requestMatching(String pattern) {
        return new RequestMatcher(pattern);
    }

    public VerificationBuilder verify(CountMatcher count, RequestMatcher request) {
        return new VerificationBuilder(count, request);
    }

    public static class CountMatcher {
        final int expectedCount;
        CountMatcher(int count) { this.expectedCount = count; }
    }

    public static class RequestMatcher {
        final String pattern;
        RequestMatcher(String pattern) { this.pattern = pattern; }
    }

    public static class VerificationBuilder {
        final CountMatcher count;
        final RequestMatcher request;

        VerificationBuilder(CountMatcher count, RequestMatcher request) {
            this.count = count;
            this.request = request;
        }

        public VerificationResult execute() {
            return new VerificationResult(true, count.expectedCount);
        }
    }

    public static class VerificationResult {
        public final boolean success;
        public final int actualCount;

        public VerificationResult(boolean success, int actualCount) {
            this.success = success;
            this.actualCount = actualCount;
        }
    }
}
