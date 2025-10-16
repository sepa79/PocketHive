package io.pockethive.controlplane.spring;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Exposes the externally supplied bee identity resolved from Spring configuration.
 */
@Validated
@ConfigurationProperties(prefix = "pockethive.control-plane.worker")
public class BeeIdentityProperties {

    public static final String INSTANCE_ID_PROPERTY = "pockethive.control-plane.worker.instance-id";

    @NotBlank(message = INSTANCE_ID_PROPERTY + " must not be blank")
    private String instanceId;

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    /**
     * Returns the resolved bee instance identifier.
     */
    public String beeName() {
        return instanceId;
    }
}

