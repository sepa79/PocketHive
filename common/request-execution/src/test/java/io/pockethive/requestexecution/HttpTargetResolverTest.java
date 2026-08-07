package io.pockethive.requestexecution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class HttpTargetResolverTest {

  @Test
  void appendsARelativePathToTheConfiguredBaseUrl() {
    URI target = HttpTargetResolver.resolve(URI.create("http://target.example/api"), "/orders");

    assertThat(target).isEqualTo(URI.create("http://target.example/api/orders"));
  }

  @Test
  void acceptsAnAbsoluteHttpPathWithoutUsingTheConfiguredBaseUrl() {
    URI target = HttpTargetResolver.resolve("http://target.example/api", "https://other.example/orders");

    assertThat(target).isEqualTo(URI.create("https://other.example/orders"));
  }

  @Test
  void rejectsANonHttpAbsolutePath() {
    assertThatThrownBy(() -> HttpTargetResolver.resolve("http://target.example/api", "ftp://other.example/orders"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("target must use http or https");
  }

  @Test
  void rejectsARelativePathWithoutAnExplicitBaseUrl() {
    assertThatThrownBy(() -> HttpTargetResolver.resolve((String) null, "/orders"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUrl must not be blank");
  }
}
