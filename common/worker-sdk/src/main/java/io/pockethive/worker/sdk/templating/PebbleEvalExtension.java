package io.pockethive.worker.sdk.templating;

import com.mitchellbosecke.pebble.extension.AbstractExtension;
import com.mitchellbosecke.pebble.extension.Function;
import com.mitchellbosecke.pebble.template.EvaluationContext;
import com.mitchellbosecke.pebble.template.PebbleTemplate;
import io.pockethive.worker.sdk.api.WorkItem;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class PebbleEvalExtension extends AbstractExtension {

    private final Function evalFunction;

    PebbleEvalExtension(SpelTemplateEvaluator evaluator) {
        Objects.requireNonNull(evaluator, "evaluator");
        this.evalFunction = new EvalFunction(evaluator);
    }

    @Override
    public Map<String, Function> getFunctions() {
        return Map.of("eval", evalFunction);
    }

    private static final class EvalFunction implements Function {

        private final SpelTemplateEvaluator evaluator;

        private EvalFunction(SpelTemplateEvaluator evaluator) {
            this.evaluator = evaluator;
        }

        @Override
        public List<String> getArgumentNames() {
            return List.of("expression");
        }

        @Override
        public Object execute(Map<String, Object> args,
                              PebbleTemplate self,
                              EvaluationContext context,
                              int lineNumber) {
            String expression = args == null ? "" : Objects.toString(args.get("expression"), "");
            Map<String, Object> root = buildRoot(context);
            return evaluator.evaluate(expression, root);
        }

        private Map<String, Object> buildRoot(EvaluationContext context) {
            Map<String, Object> root = new LinkedHashMap<>();

            Object workItem = context.getVariable("workItem");
            if (workItem != null) {
                root.put("workItem", workItem);
            }

            Object payload = context.getVariable("payload");
            if (payload == null && workItem instanceof WorkItem wi) {
                payload = wi.payload();
            }
            if (payload != null) {
                root.put("payload", payload);
            }

            Object headers = context.getVariable("headers");
            if (headers == null && workItem instanceof WorkItem wi) {
                headers = wi.headers();
            }
            if (headers != null) {
                root.put("headers", headers);
            }

            Instant now = Instant.now();
            root.put("now", now);
            root.put("nowIso", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));

            return root;
        }
    }
}
