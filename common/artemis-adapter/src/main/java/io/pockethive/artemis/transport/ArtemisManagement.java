package io.pockethive.artemis.transport;

import org.apache.activemq.artemis.api.config.ActiveMQDefaultConfiguration;
import org.apache.activemq.artemis.api.core.client.ClientRequestor;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.management.ManagementHelper;
import org.apache.activemq.artemis.api.core.management.ResourceNames;

/**
 * Responsibility: encode bounded Core management operations missing from the direct session API.
 * Must not: discover targets, select connection settings or infer success from a request alone.
 * Contract: RESP-ARTEMIS-RESOURCES — docs/architecture/runtime-responsibilities.md#resp-artemis-resources.
 */
public final class ArtemisManagement {
    private static final String DELETE_ADDRESS = "deleteAddress";
    private final ClientSession session;
    private final long timeoutMillis;

    public ArtemisManagement(ClientSession session, long timeoutMillis) {
        this.session = java.util.Objects.requireNonNull(session, "session");
        this.timeoutMillis = timeoutMillis;
    }

    public void deleteAddress(String address) {
        try {
            session.start();
            try (var requestor = new ClientRequestor(session, ActiveMQDefaultConfiguration.getDefaultManagementAddress())) {
                var request = session.createMessage(false);
                ManagementHelper.putOperationInvocation(request, ResourceNames.BROKER, DELETE_ADDRESS, address, false);
                var response = requestor.request(request, timeoutMillis);
                if (response == null) {
                    throw new IllegalStateException("Artemis management response timed out");
                }
                if (!ManagementHelper.hasOperationSucceeded(response)) {
                    throw new IllegalStateException("Artemis address removal failed: " + ManagementHelper.getResult(response));
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot remove Artemis address", failure);
        }
    }
}
