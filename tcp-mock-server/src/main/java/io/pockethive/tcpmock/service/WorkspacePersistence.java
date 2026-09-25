package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.WorkspaceCatalogue;

/**
 * Responsibility: expose durable workspace snapshot IO to the catalogue owner. Must not: infer
 * defaults, identities or mutation policy. Contract: RESP-TCP-MOCK-CATALOGUE-STORAGE —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-catalogue-storage.
 */
public interface WorkspacePersistence {
  boolean hasSnapshot();

  WorkspaceCatalogue load();

  void save(WorkspaceCatalogue catalogue);
}
