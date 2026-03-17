package io.pockethive.networkproxy.infra.toxiproxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.pockethive.networkproxy.config.NetworkProxyManagerProperties;
import io.pockethive.networkproxy.app.ToxiproxyAdminClient.ProxyRecord;
import io.pockethive.networkproxy.app.ToxiproxyAdminClient.ToxicRecord;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ToxiproxyHttpClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void listProxiesParsesToxiproxyObjectResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/proxies", exchange -> respondJson(exchange, """
            {
              "sut-a__default": {
                "name": "sut-a__default",
                "listen": "0.0.0.0:18080",
                "upstream": "wiremock:8080",
                "enabled": true,
                "toxics": []
              }
            }
            """));
        server.start();

        NetworkProxyManagerProperties properties = new NetworkProxyManagerProperties();
        properties.getToxiproxy().setUrl("http://127.0.0.1:" + server.getAddress().getPort());

        ToxiproxyHttpClient client = new ToxiproxyHttpClient(new ObjectMapper(), properties);

        Map<String, ProxyRecord> proxies = client.listProxies();

        assertThat(proxies).containsOnlyKeys("sut-a__default");
        assertThat(proxies.get("sut-a__default"))
            .extracting(ProxyRecord::name, ProxyRecord::listen, ProxyRecord::upstream, ProxyRecord::enabled)
            .containsExactly("sut-a__default", "0.0.0.0:18080", "wiremock:8080", true);
    }

    @Test
    void createToxicAcceptsOkResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/proxies/sut-a__default/toxics", exchange -> {
            String response = """
                {
                  "name": "default-latency-0",
                  "type": "latency",
                  "stream": "downstream",
                  "toxicity": 1.0,
                  "attributes": {
                    "latency": 250,
                    "jitter": 25
                  }
                }
                """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();

        NetworkProxyManagerProperties properties = new NetworkProxyManagerProperties();
        properties.getToxiproxy().setUrl("http://127.0.0.1:" + server.getAddress().getPort());

        ToxiproxyHttpClient client = new ToxiproxyHttpClient(new ObjectMapper(), properties);

        ToxicRecord toxic = client.createToxic(
            "sut-a__default",
            new ToxicRecord("default-latency-0", "latency", "downstream", 1.0, new LinkedHashMap<>(Map.of(
                "latency", 250,
                "jitter", 25
            )))
        );

        assertThat(toxic)
            .extracting(ToxicRecord::name, ToxicRecord::type, ToxicRecord::stream, ToxicRecord::toxicity)
            .containsExactly("default-latency-0", "latency", "downstream", 1.0d);
        assertThat(toxic.attributes()).containsEntry("latency", 250).containsEntry("jitter", 25);
    }

    private static void respondJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
