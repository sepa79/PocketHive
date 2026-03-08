package io.pockethive.networkproxy.app;

import java.util.List;
import java.util.Map;

public interface ToxiproxyAdminClient {

    Map<String, ProxyRecord> listProxies() throws Exception;

    void deleteProxy(String proxyName) throws Exception;

    ProxyRecord createProxy(ProxyRecord proxy) throws Exception;

    ToxicRecord createToxic(String proxyName, ToxicRecord toxic) throws Exception;

    record ProxyRecord(String name, String listen, String upstream, boolean enabled) {
    }

    record ToxicRecord(String name,
                       String type,
                       String stream,
                       double toxicity,
                       Map<String, Object> attributes) {

        public ToxicRecord {
            attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
        }
    }
}
