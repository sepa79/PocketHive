package io.pockethive.tcpmock.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.pockethive.auth.contract.*;
import io.pockethive.tcpmock.controller.CurrentUserController;
import io.pockethive.tcpmock.controller.WorkspaceController;
import io.pockethive.tcpmock.service.WorkspaceFileStore;
import io.pockethive.tcpmock.service.WorkspaceService;
import jakarta.servlet.Filter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdministrationAuthenticationTest {
  @TempDir Path root;
  private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private WebApplicationContextRunner application(String provider) {
    return new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                WebMvcAutoConfiguration.class,
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                HttpMessageConvertersAutoConfiguration.class,
                JacksonAutoConfiguration.class))
        .withUserConfiguration(
            SecurityConfig.class,
            NativeSecurityConfig.class,
            PocketHiveSecurityConfig.class,
            TcpMockStoragePaths.class,
            WorkspaceFileStore.class,
            WorkspaceService.class,
            TcpMockIdentityResolver.class,
            WorkspaceController.class,
            CurrentUserController.class)
        .withPropertyValues(
            "tcp-mock.auth.provider=" + provider,
            "tcp-mock.data-directory=" + root,
            "tcp-mock.auth.native.username=fixture",
            "tcp-mock.auth.native.password=fixture-password");
  }

  @Test
  void nativeLoginHasNoAuthServiceDependencyAndStoresNamespacedOwnership() {
    application("NATIVE")
        .withPropertyValues("pockethive.auth.service-url=deliberately-invalid-unused")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var mvc =
                  MockMvcBuilders.webAppContextSetup(context)
                      .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                      .build();
              String credential =
                  "Basic "
                      + Base64.getEncoder()
                          .encodeToString(
                              "fixture:fixture-password".getBytes(StandardCharsets.UTF_8));
              mvc.perform(get("/api/auth/config"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.provider").value("NATIVE"));
              mvc.perform(get("/api/workspaces")).andExpect(status().isUnauthorized());
              mvc.perform(get("/api/workspaces").header("Authorization", "Bearer unaccepted"))
                  .andExpect(status().isUnauthorized());
              mvc.perform(get("/api/auth/me").header("Authorization", credential))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.subject").value("fixture"));
              mvc.perform(
                      post("/api/workspaces")
                          .header("Authorization", credential)
                          .contentType("application/json")
                          .content("{\"name\":\"Owned\",\"shared\":false,\"owner\":\"forged\"}"))
                  .andExpect(status().isCreated())
                  .andExpect(jsonPath("$.owner").value("NATIVE:fixture"));
              assertEquals(
                  "NATIVE:fixture",
                  new WorkspaceService(new WorkspaceFileStore(root)).findAll().getLast().owner());
            });
  }

  @Test
  void pocketHiveResolvesIdentityAppliesGlobalGrantsAndFailsClosedWithoutFallback()
      throws Exception {
    var calls = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/auth/resolve",
        exchange -> {
          calls.incrementAndGet();
          String token = exchange.getRequestHeaders().getFirst("Authorization");
          if ("Bearer invalid".equals(token)) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
          }
          String permission =
              "Bearer view".equals(token)
                  ? PocketHivePermissionIds.VIEW
                  : PocketHivePermissionIds.ALL;
          var user =
              new AuthenticatedUserDto(
                  USER,
                  "local-admin",
                  "Local Admin",
                  !"Bearer inactive".equals(token),
                  AuthProvider.DEV,
                  List.of(
                      new AuthGrantDto(
                          AuthProduct.POCKETHIVE,
                          permission,
                          PocketHiveResourceTypes.DEPLOYMENT,
                          PocketHiveResourceSelectors.GLOBAL)));
          byte[] body = new ObjectMapper().writeValueAsBytes(user);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      application("POCKETHIVE")
          .withPropertyValues(
              "pockethive.auth.service-url=http://127.0.0.1:" + server.getAddress().getPort(),
              "pockethive.auth.connect-timeout=100ms",
              "pockethive.auth.read-timeout=1s")
          .run(
              context -> {
                assertNull(context.getStartupFailure());
                var mvc =
                    MockMvcBuilders.webAppContextSetup(context)
                        .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                        .build();
                mvc.perform(get("/api/auth/config"))
                    .andExpect(jsonPath("$.provider").value("POCKETHIVE"));
                mvc.perform(get("/api/workspaces").header("Authorization", "Basic ignored"))
                    .andExpect(status().isUnauthorized());
                assertEquals(0, calls.get());
                mvc.perform(get("/api/workspaces").header("Authorization", "Bearer invalid"))
                    .andExpect(status().isUnauthorized());
                mvc.perform(get("/api/workspaces").header("Authorization", "Bearer inactive"))
                    .andExpect(status().isForbidden());
                mvc.perform(get("/api/workspaces").header("Authorization", "Bearer view"))
                    .andExpect(status().isOk());
                mvc.perform(
                        post("/api/workspaces")
                            .header("Authorization", "Bearer view")
                            .contentType("application/json")
                            .content("{\"name\":\"Denied\",\"shared\":false}"))
                    .andExpect(status().isForbidden());
                mvc.perform(
                        post("/api/workspaces")
                            .header("Authorization", "Bearer admin")
                            .contentType("application/json")
                            .content("{\"name\":\"Owned\",\"shared\":false,\"owner\":\"forged\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.owner").value("POCKETHIVE:" + USER));
                var service = context.getBean(WorkspaceService.class);
                var before = service.findAll();
                server.stop(0);
                mvc.perform(
                        post("/api/workspaces")
                            .header("Authorization", "Bearer admin")
                            .contentType("application/json")
                            .content("{\"name\":\"Unavailable\",\"shared\":false}"))
                    .andExpect(status().isServiceUnavailable());
                assertEquals(before, service.findAll());
                assertEquals(before, new WorkspaceService(new WorkspaceFileStore(root)).findAll());
              });
    } finally {
      server.stop(0);
    }
  }

  @Test
  void invalidProviderAndMissingPocketHiveSettingsFailStartup() {
    application("UNKNOWN").run(context -> assertNotNull(context.getStartupFailure()));
    application("POCKETHIVE").run(context -> assertNotNull(context.getStartupFailure()));
  }
}
