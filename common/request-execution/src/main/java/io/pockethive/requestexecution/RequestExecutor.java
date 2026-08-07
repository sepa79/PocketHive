package io.pockethive.requestexecution;

/** Executes one fully rendered HTTP request. */
@FunctionalInterface
public interface RequestExecutor {
  HttpExecutionResult execute(HttpExecutionRequest request) throws Exception;
}
