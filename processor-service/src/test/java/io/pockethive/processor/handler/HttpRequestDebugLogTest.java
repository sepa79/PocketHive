package io.pockethive.processor.handler;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpRequestDebugLogTest {
  @ParameterizedTest
  @ValueSource(strings = {"Authorization", "authorization", "aUtHoRiZaTiOn",
      "Proxy-Authorization", "proxy-authorization", "pRoXy-AuThOrIzAtIoN",
      "Cookie", "cookie", "cOoKiE", "Set-Cookie", "set-cookie", "sEt-CoOkIe"})
  void redactsCompleteCredentialsBeforeLoggingWithoutChangingTransportHeaders(String headerName) {
    String credential = "Basic disposable-credential-A9+/=";
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(headerName, credential);
    headers.put("X-Audit-Probe", "safe-probe");
    Map<String, String> originalHeaders = Map.copyOf(headers);

    LoggerContext logContext = new LoggerContext();
    Logger logger = logContext.getLogger(HttpRequestDebugLogTest.class.getName());
    logger.setLevel(Level.DEBUG);
    logger.setAdditive(false);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.setContext(logContext);
    captured.start();
    logger.addAppender(captured);
    try {
      HttpRequestDebugLog.log(logger, "POST", URI.create("https://audit-resource.invalid/protected"),
          headers, "safe-request-body");

      assertThat(headers).as("redaction must not change the headers used by transport").isEqualTo(originalHeaders);
      List<String> messages = captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
      assertThat(messages).anyMatch(message -> message.contains(headerName) && message.contains("[REDACTED]"));
      assertThat(messages).anyMatch(message -> message.contains("X-Audit-Probe") && message.contains("safe-probe"));
      assertThat(messages).anyMatch(message -> message.contains("HTTP REQUEST POST")
          && message.contains("[REDACTED]") && message.contains("safe-request-body"));
      assertThat(messages).noneMatch(message -> message.contains(credential));
      assertThat(captured.list.stream().flatMap(event -> Arrays.stream(event.getArgumentArray()))
          .map(String::valueOf).toList())
          .as("credential values must not enter raw logging arguments")
          .noneMatch(argument -> argument.contains(credential));
    } finally {
      logger.detachAppender(captured);
      captured.stop();
      logContext.stop();
    }
  }
}
