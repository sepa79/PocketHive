package io.pockethive.networkproxy.infra.haproxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.networkproxy.app.HaproxyAdminClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class HaproxyConfigClientTest {

    @Test
    void renderConfigIncludesHealthcheckAndTcpRoutes() {
        String config = HaproxyConfigClient.renderConfig(List.of(
            new HaproxyAdminClient.RouteRecord("sut-a__payments", "0.0.0.0:9443", "toxiproxy:19443"),
            new HaproxyAdminClient.RouteRecord("sut-b__default", "0.0.0.0:18080", "toxiproxy:28080")));

        assertThat(config).contains("frontend healthcheck");
        assertThat(config).contains("bind *:8404");
        assertThat(config).contains("frontend ingress_sut_a__payments");
        assertThat(config).contains("bind 0.0.0.0:9443");
        assertThat(config).contains("server toxiproxy_sut_a__payments toxiproxy:19443 check");
        assertThat(config).contains("frontend ingress_sut_b__default");
        assertThat(config).contains("server toxiproxy_sut_b__default toxiproxy:28080 check");
    }
}
