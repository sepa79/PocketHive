package io.pockethive.templating;

import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.support.StandardTypeLocator;

/**
 * Responsibility: reject type access from template expressions.
 * Must not: load application classes for expression callers.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
final class BlockingTypeLocator extends StandardTypeLocator {
  @Override
  public Class<?> findType(String typeName) {
    throw new SpelEvaluationException(SpelMessage.TYPE_NOT_FOUND, typeName);
  }
}
