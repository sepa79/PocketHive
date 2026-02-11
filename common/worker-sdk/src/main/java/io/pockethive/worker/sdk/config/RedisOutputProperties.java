package io.pockethive.worker.sdk.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis output configuration bound from {@code pockethive.outputs.redis.*}.
 */
public class RedisOutputProperties implements WorkOutputConfig {

    private String host;
    private int port = 6379;
    private String username;
    private String password;
    private boolean ssl = false;
    private String sourceStep = "LAST";
    private String pushDirection = "RPUSH";
    private List<Route> routes = List.of();
    private String defaultList;
    private String targetListTemplate;
    private int maxLen = -1;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = normalise(host);
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = Math.max(1, port);
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = normalise(username);
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = normalise(password);
    }

    public boolean isSsl() {
        return ssl;
    }

    public void setSsl(boolean ssl) {
        this.ssl = ssl;
    }

    public String getSourceStep() {
        return sourceStep;
    }

    public void setSourceStep(String sourceStep) {
        String normalised = normalise(sourceStep);
        this.sourceStep = normalised == null ? "LAST" : normalised;
    }

    public String getPushDirection() {
        return pushDirection;
    }

    public void setPushDirection(String pushDirection) {
        String normalised = normalise(pushDirection);
        this.pushDirection = normalised == null ? "RPUSH" : normalised;
    }

    public List<Route> getRoutes() {
        return routes;
    }

    public void setRoutes(List<Route> routes) {
        if (routes == null || routes.isEmpty()) {
            this.routes = List.of();
            return;
        }
        List<Route> copy = new ArrayList<>(routes.size());
        for (Route route : routes) {
            if (route == null) {
                continue;
            }
            Route routeCopy = new Route();
            routeCopy.setMatch(route.getMatch());
            routeCopy.setHeader(route.getHeader());
            routeCopy.setHeaderMatch(route.getHeaderMatch());
            routeCopy.setList(route.getList());
            copy.add(routeCopy);
        }
        this.routes = List.copyOf(copy);
    }

    public String getDefaultList() {
        return defaultList;
    }

    public void setDefaultList(String defaultList) {
        this.defaultList = normalise(defaultList);
    }

    public String getTargetListTemplate() {
        return targetListTemplate;
    }

    public void setTargetListTemplate(String targetListTemplate) {
        this.targetListTemplate = normalise(targetListTemplate);
    }

    public int getMaxLen() {
        return maxLen;
    }

    public void setMaxLen(int maxLen) {
        this.maxLen = maxLen;
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class Route {
        private String match;
        private String header;
        private String headerMatch;
        private String list;

        public String getMatch() {
            return match;
        }

        public void setMatch(String match) {
            this.match = normalise(match);
        }

        public String getHeader() {
            return header;
        }

        public void setHeader(String header) {
            this.header = normalise(header);
        }

        public String getHeaderMatch() {
            return headerMatch;
        }

        public void setHeaderMatch(String headerMatch) {
            this.headerMatch = normalise(headerMatch);
        }

        public String getList() {
            return list;
        }

        public void setList(String list) {
            this.list = normalise(list);
        }
    }
}
