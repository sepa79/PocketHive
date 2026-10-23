package io.pockethive.orchestrator.planner;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PocketPlannerClientTest {
    private HttpServer server;
    private PocketPlannerClient client;
    private String body;
    private int status;
    private String authorization;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void setup() throws Exception {
        body = """
            {"schemaVersion":1,"instanceId":"qa","revision":7,"plans":[
            {"id":"plan","name":"Plan","testPlan":"pp:qa:plan","streams":[{"id":"stream","name":"Stream"}],
            "activities":[{"id":"minimal","name":"Minimal","streamId":"stream","tag":"pp.activity:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}]}]}
            """;
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/integration/catalogue", exchange -> {
            requests.incrementAndGet();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            exchange.getResponseHeaders().set("Location", "/redirect-target");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.createContext("/redirect-target", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        client = new PocketPlannerClient("http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper());
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void resolvesOwnerSuppliedMetadataWithoutWritingOrInventingReferences() {
        var link = client.resolve(new PlannerSelection("qa", 7, "plan", "stream", "minimal"), "Bearer session");
        assertThat(link.testPlan()).isEqualTo("pp:qa:plan");
        assertThat(link.activityTag()).isEqualTo("pp.activity:" + "A".repeat(43));
        assertThat(authorization).isEqualTo("Bearer session");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void rejectsChangedRevisionInstanceAndInvalidMembership() {
        assertStatus(() -> client.resolve(new PlannerSelection("qa", 6, "plan", "stream", "minimal"), "Bearer session"), 409);
        assertStatus(() -> client.resolve(new PlannerSelection("other", 7, "plan", "stream", "minimal"), "Bearer session"), 409);
        assertStatus(() -> client.resolve(new PlannerSelection("qa", 7, "missing", "stream", "minimal"), "Bearer session"), 400);
        assertStatus(() -> client.resolve(new PlannerSelection("qa", 7, "plan", "stream", "missing"), "Bearer session"), 400);
        body = body.replace("\"streamId\":\"stream\"", "\"streamId\":\"other\"");
        assertStatus(() -> client.resolve(new PlannerSelection("qa", 7, "plan", "stream", "minimal"), "Bearer session"), 400);
    }

    @Test
    void neverFollowsRedirectsOrTreatsAnUnavailableCatalogueAsEmpty() {
        status = 302;
        assertStatus(() -> client.catalogue("Bearer session"), 503);
        assertThat(requests.get()).isEqualTo(1);
        status = 401;
        assertStatus(() -> client.catalogue("Bearer session"), 401);
        status = 403;
        assertStatus(() -> client.catalogue("Bearer session"), 403);
        status = 200;
        body = "{}";
        assertStatus(() -> client.catalogue("Bearer session"), 503);
        body = " ";
        assertStatus(() -> client.catalogue("Bearer session"), 503);
    }

    @Test
    void rejectsMissingSessionAndUnsafeConfigurationBeforeCallingTheOwner() {
        assertStatus(() -> client.catalogue(null), 401);
        for (String base : new String[]{"", "file:///etc/passwd", "http://user:pass@localhost", "http://localhost?target=other"}) {
            var invalid = new PocketPlannerClient(base, new ObjectMapper());
            assertStatus(() -> invalid.catalogue("Bearer session"), 503);
        }
        assertThat(requests.get()).isZero();
    }

    private static void assertStatus(Runnable action, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(status));
    }
}
