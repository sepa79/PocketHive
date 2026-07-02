package io.pockethive.worker.sdk.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis output configuration bound from {@code pockethive.outputs.redis.*}.
 */
public class RedisOutputProperties implements WorkOutputConfig {

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;
    private static final int MIN_MAX_LEN = -1;

    private String host;
    private Integer port;
    private String username;
    private String password;
    private Boolean ssl;
    private String sourceStep;
    private String pushDirection;
    private List<Route> routes;
    private String defaultList;
    private String targetListTemplate;
    private Integer maxLen;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = normalise(host);
    }

    public int getPort() {
        return requirePresent(port, "port");
    }

    public void setPort(int port) {
        this.port = port;
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
        return requirePresent(ssl, "ssl");
    }

    public void setSsl(boolean ssl) {
        this.ssl = ssl;
    }

    public String getSourceStep() {
        return requireNonBlank(sourceStep, "sourceStep");
    }

    public void setSourceStep(String sourceStep) {
        this.sourceStep = normalise(sourceStep);
    }

    public String getPushDirection() {
        return requireNonBlank(pushDirection, "pushDirection");
    }

    public void setPushDirection(String pushDirection) {
        this.pushDirection = normalise(pushDirection);
    }

    public List<Route> getRoutes() {
        return routes == null ? List.of() : routes;
    }

    public void setRoutes(List<Route> routes) {
        if (routes == null || routes.isEmpty()) {
            this.routes = List.of();
            return;
        }
        List<Route> copy = new ArrayList<>(routes.size());
        int index = 0;
        for (Route route : routes) {
            if (route == null) {
                throw new IllegalArgumentException("routes[" + index + "] must be an object");
            }
            Route routeCopy = new Route();
            routeCopy.setMatch(route.getMatch());
            routeCopy.setHeader(route.getHeader());
            routeCopy.setHeaderMatch(route.getHeaderMatch());
            routeCopy.setList(route.getList());
            copy.add(routeCopy);
            index++;
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
        return requirePresent(maxLen, "maxLen");
    }

    public void setMaxLen(int maxLen) {
        this.maxLen = maxLen;
    }

    @Override
    public void validateConfigured(String prefix) {
        requireNonBlank(host, prefix + ".host");
        requirePort(port, prefix + ".port");
        requirePresent(ssl, prefix + ".ssl");
        requireNonBlank(sourceStep, prefix + ".sourceStep");
        requireNonBlank(pushDirection, prefix + ".pushDirection");
        requireMaxLen(maxLen, prefix + ".maxLen");
        if (getRoutes().isEmpty() && defaultList == null && targetListTemplate == null) {
            throw new IllegalStateException(
                prefix + " must configure routes, targetListTemplate, or defaultList");
        }
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured");
        }
        return value;
    }

    private static <T> T requirePresent(T value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " must be configured");
        }
        return value;
    }

    private static int requirePort(Integer value, String name) {
        int port = requirePresent(value, name);
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalStateException(name + " must be between " + MIN_PORT + " and " + MAX_PORT);
        }
        return port;
    }

    private static int requireMaxLen(Integer value, String name) {
        int configuredMaxLen = requirePresent(value, name);
        if (configuredMaxLen < MIN_MAX_LEN) {
            throw new IllegalStateException(name + " must be " + MIN_MAX_LEN + " or greater");
        }
        return configuredMaxLen;
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
