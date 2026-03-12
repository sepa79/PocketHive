package io.pockethive.networkproxy.app;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pockethive.swarm.model.NetworkProfile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NetworkBindingControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    NetworkProfileClient profileClient;

    @MockBean
    ToxiproxyAdminClient toxiproxy;

    @MockBean
    HaproxyAdminClient haproxy;

    @BeforeEach
    void setUp() throws Exception {
        when(profileClient.fetch(eq("passthrough")))
            .thenReturn(new NetworkProfile("passthrough", "Passthrough", List.of(), List.of("payments")));
        when(toxiproxy.listProxies()).thenReturn(Map.of());
        when(toxiproxy.createProxy(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toxiproxy.createToxic(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        Mockito.doNothing().when(toxiproxy).deleteProxy(any());
        Mockito.doNothing().when(toxiproxy).deleteToxic(any(), any());
        Mockito.doNothing().when(haproxy).applyRoutes(any());
    }

    @Test
    void bindAndClearFlow() throws Exception {
        mvc.perform(post("/api/network/bindings/swarm-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sutId": "sut-a",
                      "networkMode": "PROXIED",
                      "networkProfileId": "passthrough",
                      "requestedBy": "orchestrator",
                      "resolvedSut": {
                        "sutId": "sut-a",
                        "name": "SUT A",
                        "endpoints": {
                          "payments": {
                            "endpointId": "payments",
                            "kind": "HTTPS",
                            "clientBaseUrl": "https://proxy.local:9443",
                            "clientAuthority": "proxy.local:9443",
                            "upstreamAuthority": "internal-alb:443"
                          }
                        }
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.swarmId").value("swarm-a"))
            .andExpect(jsonPath("$.networkMode").value("PROXIED"))
            .andExpect(jsonPath("$.affectedEndpoints[0].endpointId").value("payments"));

        mvc.perform(get("/api/network/proxies"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].swarmId").value("swarm-a"));

        mvc.perform(post("/api/network/bindings/swarm-a/clear")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sutId": "sut-a",
                      "requestedBy": "ui"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.networkMode").value("DIRECT"));

        mvc.perform(get("/api/network/bindings/swarm-a"))
            .andExpect(status().isNotFound());
    }

    @Test
    void manualOverrideEndpointsWork() throws Exception {
        mvc.perform(get("/api/network/manual-override"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(put("/api/network/manual-override")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "enabled": true,
                      "latencyMs": 300,
                      "jitterMs": 30,
                      "bandwidthKbps": 2048,
                      "slowCloseDelayMs": 1200,
                      "limitDataBytes": 65536,
                      "requestedBy": "hive",
                      "reason": "live tuning"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.latencyMs").value(300))
            .andExpect(jsonPath("$.bandwidthKbps").value(2048))
            .andExpect(jsonPath("$.slowCloseDelayMs").value(1200))
            .andExpect(jsonPath("$.limitDataBytes").value(65536));

        mvc.perform(post("/api/network/manual-override/drop-connections")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "requestedBy": "hive",
                      "reason": "operator action"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void clearReturnsBadRequestForMismatchedSutId() throws Exception {
        mvc.perform(post("/api/network/bindings/swarm-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sutId": "sut-a",
                      "networkMode": "PROXIED",
                      "networkProfileId": "passthrough",
                      "requestedBy": "orchestrator",
                      "resolvedSut": {
                        "sutId": "sut-a",
                        "name": "SUT A",
                        "endpoints": {
                          "payments": {
                            "endpointId": "payments",
                            "kind": "HTTPS",
                            "clientBaseUrl": "https://proxy.local:9443",
                            "clientAuthority": "proxy.local:9443",
                            "upstreamAuthority": "internal-alb:443"
                          }
                        }
                      }
                    }
                    """))
            .andExpect(status().isOk());

        mvc.perform(post("/api/network/bindings/swarm-a/clear")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sutId": "sut-b",
                      "requestedBy": "ui"
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void clearReturnsNotFoundForMissingBinding() throws Exception {
        mvc.perform(post("/api/network/bindings/missing-swarm/clear")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sutId": "sut-a",
                      "requestedBy": "ui"
                    }
                    """))
            .andExpect(status().isNotFound());
    }

    @Test
    void bindReturnsBadRequestWhenChangingSutForExistingSwarm() throws Exception {
        mvc.perform(post("/api/network/bindings/swarm-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sutId": "sut-a",
                      "networkMode": "PROXIED",
                      "networkProfileId": "passthrough",
                      "requestedBy": "orchestrator",
                      "resolvedSut": {
                        "sutId": "sut-a",
                        "name": "SUT A",
                        "endpoints": {
                          "payments": {
                            "endpointId": "payments",
                            "kind": "HTTPS",
                            "clientBaseUrl": "https://proxy.local:9443",
                            "clientAuthority": "proxy.local:9443",
                            "upstreamAuthority": "internal-alb:443"
                          }
                        }
                      }
                    }
                    """))
            .andExpect(status().isOk());

        mvc.perform(post("/api/network/bindings/swarm-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sutId": "sut-b",
                      "networkMode": "PROXIED",
                      "networkProfileId": "passthrough",
                      "requestedBy": "orchestrator",
                      "resolvedSut": {
                        "sutId": "sut-b",
                        "name": "SUT B",
                        "endpoints": {
                          "payments": {
                            "endpointId": "payments",
                            "kind": "HTTPS",
                            "clientBaseUrl": "https://proxy.local:9443",
                            "clientAuthority": "proxy.local:9443",
                            "upstreamAuthority": "internal-alb-b:443"
                          }
                        }
                      }
                    }
                    """))
            .andExpect(status().isBadRequest());
    }
}
