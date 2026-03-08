package io.pockethive.networkproxy.app;

import java.util.List;

public interface HaproxyAdminClient {

    void applyRoutes(List<RouteRecord> routes) throws Exception;

    record RouteRecord(String routeId,
                       String bindAddress,
                       String backendAddress) {
    }
}
