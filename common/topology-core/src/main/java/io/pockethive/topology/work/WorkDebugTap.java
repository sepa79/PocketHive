package io.pockethive.topology.work;

import java.util.Optional;

/**
 * Responsibility: expose adapter-owned capture addresses, bytes and temporary resource lifetime.
 * Must not: infer domain outcomes, retain HTTP samples or re-resolve source addresses.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public interface WorkDebugTap extends AutoCloseable {
    String sourceGroup();
    String sourceAddress();
    String captureAddress();
    Optional<byte[]> receive();
    @Override void close();
}
