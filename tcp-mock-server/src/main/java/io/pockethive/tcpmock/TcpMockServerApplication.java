package io.pockethive.tcpmock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Responsibility: compose the established TCP application with its explicit administration
 * provider. Must not: create implicit credentials or choose an authentication fallback. Contract:
 * RESP-TCP-MOCK-AUTHENTICATION —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-authentication.
 */
@EnableConfigurationProperties
@SpringBootApplication(
    exclude =
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
            .class)
public class TcpMockServerApplication {
  public static void main(String[] args) {
    SpringApplication.run(TcpMockServerApplication.class, args);
  }
}
