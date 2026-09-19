package io.pockethive.templating;

import java.util.Map;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.TypedValue;

/**
 * Responsibility: expose map entries to constrained expression evaluation.
 * Must not: resolve beans or mutate expression root data.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
final class MapEntryAccessor implements PropertyAccessor {
  @Override
  public Class<?>[] getSpecificTargetClasses() {
    return new Class[]{Map.class};
  }

  @Override
  public boolean canRead(EvaluationContext context, Object target, String name) {
    return target instanceof Map<?, ?>;
  }

  @Override
  public TypedValue read(EvaluationContext context, Object target, String name) {
    Map<?, ?> map = (Map<?, ?>) target;
    Object value = map.get(name);
    return new TypedValue(value);
  }

  @Override
  public boolean canWrite(EvaluationContext context, Object target, String name) {
    return false;
  }

  @Override
  public void write(EvaluationContext context, Object target, String name, Object newValue) {
    throw new UnsupportedOperationException("read-only map accessor");
  }
}
