package io.pockethive.templating;

import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.Function;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import java.lang.reflect.Method;
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
        this(evaluator, false);
    }

    PebbleEvalExtension(SpelTemplateEvaluator evaluator, boolean validateOnly) {
        Objects.requireNonNull(evaluator, "evaluator");
        this.evalFunction = new EvalFunction(evaluator, validateOnly);
    }

    @Override
    public Map<String, Function> getFunctions() {
        return Map.of("eval", evalFunction);
    }

    private static final class EvalFunction implements Function {

        private final SpelTemplateEvaluator evaluator;
        private final boolean validateOnly;

        private EvalFunction(SpelTemplateEvaluator evaluator, boolean validateOnly) {
            this.evaluator = evaluator;
            this.validateOnly = validateOnly;
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
            if (validateOnly) {
                evaluator.validate(expression);
                return "";
            }
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
            if (payload == null) {
                payload = accessorValue(workItem, "payload");
            }
            if (payload != null) {
                root.put("payload", payload);
            }

            Object headers = context.getVariable("headers");
            if (headers == null) {
                headers = accessorValue(workItem, "headers");
            }
            if (headers != null) {
                root.put("headers", headers);
            }

            Object vars = context.getVariable("vars");
            if (vars == null && headers instanceof Map<?, ?> headerMap) {
                vars = headerMap.get("vars");
            }
            if (vars != null) {
                root.put("vars", vars);
            }

            Instant now = Instant.now();
            root.put("now", now);
            root.put("nowIso", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));

            return root;
        }

        private Object accessorValue(Object target, String methodName) {
            if (target == null) {
                return null;
            }
            try {
                Method method = target.getClass().getMethod(methodName);
                return method.invoke(target);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
    }
}
