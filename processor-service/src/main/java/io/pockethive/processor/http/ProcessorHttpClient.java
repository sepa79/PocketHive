package io.pockethive.processor.http;

import io.pockethive.processor.ProcessorWorkerConfig;
import java.io.IOException;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;

/**
 * Responsibility: execute processor HTTP requests, release owned clients and expose configured capacity.
 * Must not: expose raw clients, normalize configuration or construct processor results.
 * Contract: RESP-PROCESSOR-HTTP-CLIENT — docs/architecture/runtime-responsibilities.md#resp-processor-http-client.
 */
public interface ProcessorHttpClient extends AutoCloseable {
  @Override void close();
  <T> T execute(ClassicHttpRequest request, HttpClientResponseHandler<T> responseHandler,
                ProcessorWorkerConfig config) throws IOException;

  int maxConnections(ProcessorWorkerConfig config);
}
