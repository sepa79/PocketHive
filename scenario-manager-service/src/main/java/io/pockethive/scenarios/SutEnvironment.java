package io.pockethive.scenarios;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.Map;

/**
 * System Under Test (SUT) environment definition.
 * <p>
 * Environments describe concrete deployment targets such as "WireMock (local)"
 * or "Paytech Reltest". They are resolved by id and expose a set of named
 * endpoints that workers can bind to.
 */
public final class SutEnvironment {

    private final String id;
    private final String name;
    private final String type;
    private final Map<String, SutEndpoint> endpoints;
    private final Ui ui;

    @JsonCreator
    public SutEnvironment(@JsonProperty("id") String id,
                          @JsonProperty("name") String name,
                          @JsonProperty("type") String type,
                          @JsonProperty("endpoints") Map<String, SutEndpoint> endpoints,
                          @JsonProperty("ui") Ui ui) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.endpoints = endpoints == null ? Map.of() : Map.copyOf(endpoints);
        this.ui = ui;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /**
     * Free-form environment type label, e.g. {@code dev}, {@code uat},
     * {@code prodlike}, {@code sandbox}.
     */
    public String getType() {
        return type;
    }

    /**
     * Named endpoints available in this environment.
     */
    public Map<String, SutEndpoint> getEndpoints() {
        return Collections.unmodifiableMap(endpoints);
    }

    /**
     * Optional UI hints for rendering this environment in the Hive UI.
     */
    public Ui getUi() {
        return ui;
    }

    public static final class Ui {
        private final String panelId;

        @JsonCreator
        public Ui(@JsonProperty("panelId") String panelId) {
          this.panelId = panelId;
        }

        public String getPanelId() {
          return panelId;
        }
    }
}
