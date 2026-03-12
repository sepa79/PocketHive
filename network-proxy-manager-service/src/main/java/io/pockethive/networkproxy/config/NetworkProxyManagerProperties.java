package io.pockethive.networkproxy.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pockethive.network-proxy-manager")
public class NetworkProxyManagerProperties {

    private final Http http = new Http();
    private final ScenarioManager scenarioManager = new ScenarioManager();
    private final Toxiproxy toxiproxy = new Toxiproxy();
    private final Haproxy haproxy = new Haproxy();

    public Http getHttp() {
        return http;
    }

    public ScenarioManager getScenarioManager() {
        return scenarioManager;
    }

    public Toxiproxy getToxiproxy() {
        return toxiproxy;
    }

    public Haproxy getHaproxy() {
        return haproxy;
    }

    public static class Http {
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(30);

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    public static class ScenarioManager {
        private String url = "http://scenario-manager:8080";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    public static class Toxiproxy {
        private String url = "http://toxiproxy:8474";
        private String listenHost = "0.0.0.0";
        private int listenPortOffset = 10_000;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getListenHost() {
            return listenHost;
        }

        public void setListenHost(String listenHost) {
            this.listenHost = listenHost;
        }

        public int getListenPortOffset() {
            return listenPortOffset;
        }

        public void setListenPortOffset(int listenPortOffset) {
            this.listenPortOffset = listenPortOffset;
        }
    }

    public static class Haproxy {
        private String configFile = "/opt/haproxy-runtime/haproxy.cfg";
        private String backendHost = "toxiproxy";

        public String getConfigFile() {
            return configFile;
        }

        public void setConfigFile(String configFile) {
            this.configFile = configFile;
        }

        public String getBackendHost() {
            return backendHost;
        }

        public void setBackendHost(String backendHost) {
            this.backendHost = backendHost;
        }
    }
}
