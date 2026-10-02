package io.pockethive.processor.mip;

/**
 * Responsibility: name the MIP adapter configuration lifetime states.
 * Must not: select or mutate a transport.
 * Contract: RESP-PROCESSOR-MIP-CONFIG — docs/architecture/runtime-responsibilities.md#resp-processor-mip-config.
 */
public enum MipRuntimeState { UNCONFIGURED, CLIENT, ACTIVE, RETIRED }
