package io.pockethive.topology.control;

/**
 * Responsibility: resolve physical queue names for Control topology consumers.
 * Must not: select recipients, build event routing keys or perform broker operations.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public interface ControlResourceNamesPort {
    String workerControlQueue(String prefix, String swarmId, String role, String instanceId);
    String swarmControllerQueue(String prefix, String swarmId, String role, String instanceId);
    String managerControlQueue(String prefix, String role, String instanceId);
    String controllerStatusQueue(String prefix, String instanceId);
}
