package io.pockethive.controlplane.spring;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Exposes the externally supplied bee identity resolved from Spring configuration.
 */
@ConfigurationProperties(prefix = "pockethive.control-plane.worker")
public class BeeIdentityProperties implements InitializingBean {

    public static final String INSTANCE_ID_PROPERTY = "pockethive.control-plane.worker.instance-id";

    private boolean enabled = true;
    private String instanceId;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

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
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalStateException(INSTANCE_ID_PROPERTY + " must not be null or blank");
        }
        return instanceId;
    }

    @Override
    public void afterPropertiesSet() {
        if (enabled && (instanceId == null || instanceId.isBlank())) {
            throw new IllegalStateException(
                INSTANCE_ID_PROPERTY + " must be provided when the worker control plane is enabled");
        }
    }
}

