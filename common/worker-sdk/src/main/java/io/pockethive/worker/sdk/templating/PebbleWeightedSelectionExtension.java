package io.pockethive.worker.sdk.templating;

import com.mitchellbosecke.pebble.extension.AbstractExtension;
import com.mitchellbosecke.pebble.extension.Function;
import com.mitchellbosecke.pebble.template.EvaluationContext;
import com.mitchellbosecke.pebble.template.PebbleTemplate;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

final class PebbleWeightedSelectionExtension extends AbstractExtension {

    private final SeededSelector seededSelector;

    PebbleWeightedSelectionExtension(SeededSelector seededSelector) {
        this.seededSelector = Objects.requireNonNull(seededSelector, "seededSelector");
    }

    @Override
    public Map<String, Function> getFunctions() {
        return Map.of(
            "pickWeighted", new PickWeightedFunction(),
            "pickWeightedSeeded", new PickWeightedSeededFunction(seededSelector)
        );
    }

    private static final class PickWeightedFunction implements Function {

        @Override
        public List<String> getArgumentNames() {
            return null;
        }

        @Override
        public Object execute(Map<String, Object> args,
                              PebbleTemplate self,
                              EvaluationContext context,
                              int lineNumber) {
            OrderedArgs ordered = OrderedArgs.from(args);
            return selectWeighted(bound -> ThreadLocalRandom.current().nextInt(bound), ordered, 0);
        }
    }

    private static final class PickWeightedSeededFunction implements Function {

        private final SeededSelector seededSelector;

        private PickWeightedSeededFunction(SeededSelector seededSelector) {
            this.seededSelector = seededSelector;
        }

        @Override
        public List<String> getArgumentNames() {
            return null;
        }

        @Override
        public Object execute(Map<String, Object> args,
                              PebbleTemplate self,
                              EvaluationContext context,
                              int lineNumber) {
            OrderedArgs ordered = OrderedArgs.from(args);
            if (ordered.size() < 4) {
                throw new TemplateRenderingException("pickWeightedSeeded requires at least 4 arguments: label, seed, value, weight");
            }
            String label = ordered.stringAt(0, "label");
            String seed = ordered.stringAt(1, "seed");
            return selectWeighted(bound -> seededSelector.nextInt(label, seed, bound), ordered, 2);
        }
    }

    @FunctionalInterface
    private interface RandomIntSource {
        int nextInt(int bound);
    }

    private static Object selectWeighted(RandomIntSource randomSource, OrderedArgs ordered, int offset) {
        int argCount = ordered.size() - offset;
        if (argCount < 2 || (argCount % 2) != 0) {
            throw new TemplateRenderingException("pickWeighted expects (value, weight) pairs");
        }
        int pairCount = argCount / 2;
        int[] weights = new int[pairCount];
        Object[] values = new Object[pairCount];
        int sum = 0;
        for (int i = 0; i < pairCount; i++) {
            int valueIndex = offset + i * 2;
            int weightIndex = valueIndex + 1;
            Object value = ordered.at(valueIndex);
            if (value == null) {
                throw new TemplateRenderingException("pickWeighted value must not be null");
            }
            int weight = ordered.intAt(weightIndex, "weight");
            if (weight < 0) {
                throw new TemplateRenderingException("pickWeighted weight must be >= 0");
            }
            values[i] = value;
            weights[i] = weight;
            sum = safeAdd(sum, weight);
        }
        if (sum <= 0) {
            throw new TemplateRenderingException("pickWeighted sum of weights must be > 0");
        }
        int roll = randomSource.nextInt(sum);
        int cursor = roll;
        for (int i = 0; i < pairCount; i++) {
            int weight = weights[i];
            if (weight <= 0) {
                continue;
            }
            if (cursor < weight) {
                return values[i];
            }
            cursor -= weight;
        }
        return values[pairCount - 1];
    }

    private static int safeAdd(int total, int add) {
        long sum = (long) total + (long) add;
        if (sum > Integer.MAX_VALUE) {
            throw new TemplateRenderingException("pickWeighted total weight is too large");
        }
        return (int) sum;
    }

    private record OrderedArgs(Map<String, Object> values) {

        static OrderedArgs from(Map<String, Object> args) {
            if (args == null || args.isEmpty()) {
                return new OrderedArgs(Map.of());
            }
            return new OrderedArgs(args);
        }

        int size() {
            if (values.isEmpty()) {
                return 0;
            }
            int i = 0;
            while (values.containsKey(String.valueOf(i))) {
                i++;
            }
            return i;
        }

        Object at(int index) {
            return values.get(String.valueOf(index));
        }

        String stringAt(int index, String label) {
            Object value = at(index);
            String text = value == null ? "" : value.toString();
            if (text.isBlank()) {
                throw new TemplateRenderingException("pickWeightedSeeded " + label + " must not be blank");
            }
            return text.trim();
        }

        int intAt(int index, String label) {
            Object value = at(index);
            if (value instanceof Number num) {
                return num.intValue();
            }
            if (value instanceof String text) {
                String trimmed = text.trim();
                if (trimmed.isEmpty()) {
                    throw new TemplateRenderingException("pickWeighted " + label + " must be an integer");
                }
                try {
                    return Integer.parseInt(trimmed);
                } catch (NumberFormatException ex) {
                    throw new TemplateRenderingException("pickWeighted " + label + " must be an integer", ex);
                }
            }
            throw new TemplateRenderingException("pickWeighted " + label + " must be an integer");
        }
    }

    static final class SeededSelector {

        private final ConcurrentHashMap<String, SeededStream> streams = new ConcurrentHashMap<>();

        int nextInt(String label, String seed, int bound) {
            String key = label.trim();
            SeededStream stream = streams.computeIfAbsent(key, ignored -> new SeededStream(label, seed));
            return stream.nextInt(bound);
        }

        void reset() {
            streams.clear();
        }
    }

    private static final class SeededStream {

        private final SplittableRandom random;

        private SeededStream(String label, String seed) {
            this.random = new SplittableRandom(hashSeed(label, seed));
        }

        int nextInt(int bound) {
            synchronized (this) {
                return random.nextInt(bound);
            }
        }

        private static long hashSeed(String label, String seed) {
            String material = seed + "\u0000" + label;
            byte[] bytes = material.getBytes(StandardCharsets.UTF_8);
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(bytes);
                return ByteBuffer.wrap(hash, 0, 8).getLong();
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 not available", ex);
            }
        }
    }
}
