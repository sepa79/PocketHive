package io.pockethive.httpsequence;

interface HttpCallExecutor {

  HttpCallResult execute(String baseUrl, RenderedCall call) throws Exception;

  record RenderedCall(String method, String path, String body, java.util.Map<String, String> headers) {
  }

  record HttpCallResult(int statusCode,
                        java.util.Map<String, java.util.List<String>> headers,
                        String body,
                        String error) {
  }
}

