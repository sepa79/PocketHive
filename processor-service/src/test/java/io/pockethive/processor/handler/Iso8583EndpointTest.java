package io.pockethive.processor.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Iso8583EndpointTest {
  @Test
  void selectsServerFromExplicitSchemeAndKeepsOtherWorkerProtocolsAvailable() {
    assertThat(Iso8583Endpoint.selectsMipServer("MIP://127.0.0.1:6036")).isTrue();
    assertThat(Iso8583Endpoint.selectsMipServer("http://sut/api")).isFalse();
    assertThat(Iso8583Endpoint.selectsMipServer("tcp://sut:6036")).isFalse();
    var endpoint = Iso8583Endpoint.parse("MIP://127.0.0.1:6036");
    assertThat(endpoint.mipServer()).isTrue();
    assertThat(endpoint.tls()).isFalse();
    assertThat(endpoint.endpoint()).isEqualTo("mip://127.0.0.1:6036");
  }

  @ParameterizedTest
  @ValueSource(strings = {"mip://sut", "mip://sut:0", "mip://sut:65536", "mip://sut:6036/path",
      "mip://user@sut:6036", "mip://sut:6036?x=1", "mip://sut:6036#fragment", "http://sut:6036"})
  void rejectsInvalidOrAmbiguousIsoEndpoints(String endpoint) {
    assertThatThrownBy(() -> Iso8583Endpoint.parse(endpoint))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid ISO8583 baseUrl");
  }

  @Test
  void malformedCredentialBearingUriDoesNotExposeInputThroughMessageOrCause() {
    String privateUri = "mip://test-user:test-secret@invalid host:6036";
    assertThatThrownBy(() -> Iso8583Endpoint.parse(privateUri))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("invalid ISO8583 baseUrl")
        .hasNoCause()
        .hasToString("java.lang.IllegalArgumentException: invalid ISO8583 baseUrl");
    assertThatThrownBy(() -> Iso8583Endpoint.selectsMipServer(privateUri))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("invalid ISO8583 baseUrl")
        .hasNoCause()
        .hasToString("java.lang.IllegalArgumentException: invalid ISO8583 baseUrl");
  }
}
