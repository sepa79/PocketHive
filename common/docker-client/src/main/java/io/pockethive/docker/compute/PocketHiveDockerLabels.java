package io.pockethive.docker.compute;

import io.pockethive.manager.runtime.ComputeAdapterType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PocketHiveDockerLabels {

  public static final String MANAGED = "pockethive.managed";
  public static final String RESOURCE_KIND = "pockethive.resourceKind";
  public static final String OWNER = "pockethive.owner";
  public static final String SWARM_ID = "pockethive.swarmId";
  public static final String RUN_ID = "pockethive.runId";
  public static final String ROLE = "pockethive.role";
  public static final String INSTANCE = "pockethive.instance";
  public static final String LOGICAL_NAME = "pockethive.logicalName";
  public static final String COMPUTE_ADAPTER = "pockethive.computeAdapter";
  public static final String IMAGE = "pockethive.image";
  public static final String VERSION = "pockethive.version";
  public static final String CREATED_AT = "pockethive.createdAt";
  public static final String TEMPLATE_ID = "pockethive.templateId";
  public static final String STACK_NAME = "pockethive.stackName";
  public static final String MANAGED_VALUE = "true";
  public static final String RESOURCE_KIND_MANAGER = "manager";
  public static final String RESOURCE_KIND_WORKER = "worker";
  public static final String OWNER_ORCHESTRATOR = "orchestrator";
  public static final String OWNER_SWARM_CONTROLLER = "swarm-controller";

  private static final String ENV_SWARM_ID = "POCKETHIVE_CONTROL_PLANE_SWARM_ID";
  private static final String ENV_MANAGER_ROLE = "POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE";
  private static final String ENV_WORKER_ROLE = "POCKETHIVE_CONTROL_PLANE_WORKER_ROLE";
  private static final String ENV_INSTANCE = "POCKETHIVE_CONTROL_PLANE_INSTANCE_ID";
  private static final String ENV_RUN_ID = "POCKETHIVE_JOURNAL_RUN_ID";
  private static final String ENV_TEMPLATE_ID = "POCKETHIVE_TEMPLATE_ID";
  private static final String ENV_STACK_NAME = "POCKETHIVE_RUNTIME_STACK_NAME";

  private PocketHiveDockerLabels() {
  }

  public static Map<String, String> managerLabels(
      String logicalName,
      String image,
      Map<String, String> env,
      ComputeAdapterType adapterType) {
    return labels(
        RESOURCE_KIND_MANAGER,
        OWNER_ORCHESTRATOR,
        logicalName,
        image,
        requireEnv(env, ENV_MANAGER_ROLE),
        env,
        adapterType);
  }

  public static Map<String, String> workerLabels(
      String logicalName,
      String image,
      Map<String, String> env,
      ComputeAdapterType adapterType) {
    return labels(
        RESOURCE_KIND_WORKER,
        OWNER_SWARM_CONTROLLER,
        logicalName,
        image,
        requireEnv(env, ENV_WORKER_ROLE),
        env,
        adapterType);
  }

  private static Map<String, String> labels(
      String resourceKind,
      String owner,
      String logicalName,
      String image,
      String role,
      Map<String, String> env,
      ComputeAdapterType adapterType) {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put(MANAGED, MANAGED_VALUE);
    labels.put(RESOURCE_KIND, resourceKind);
    labels.put(OWNER, owner);
    labels.put(SWARM_ID, requireEnv(env, ENV_SWARM_ID));
    labels.put(RUN_ID, requireEnv(env, ENV_RUN_ID));
    labels.put(ROLE, role);
    labels.put(INSTANCE, requireEnv(env, ENV_INSTANCE));
    labels.put(LOGICAL_NAME, requireNonBlank(logicalName, "logicalName"));
    labels.put(COMPUTE_ADAPTER, adapterType.name());
    labels.put(IMAGE, requireNonBlank(image, "image"));
    labels.put(CREATED_AT, Instant.now().toString());
    putIfPresent(labels, VERSION, imageTag(image));
    putIfPresent(labels, TEMPLATE_ID, env.get(ENV_TEMPLATE_ID));
    putIfPresent(labels, STACK_NAME, env.get(ENV_STACK_NAME));
    return Map.copyOf(labels);
  }

  private static String imageTag(String image) {
    String normalized = requireNonBlank(image, "image");
    int digestIndex = normalized.indexOf('@');
    String imageWithoutDigest = digestIndex >= 0 ? normalized.substring(0, digestIndex) : normalized;
    int lastSlash = imageWithoutDigest.lastIndexOf('/');
    int lastColon = imageWithoutDigest.lastIndexOf(':');
    if (lastColon <= lastSlash) {
      return null;
    }
    return requireNonBlank(imageWithoutDigest.substring(lastColon + 1), "image tag");
  }

  private static void putIfPresent(Map<String, String> labels, String key, String value) {
    if (value != null && !value.isBlank()) {
      labels.put(key, value.trim());
    }
  }

  private static String requireEnv(Map<String, String> env, String key) {
    if (env == null || env.isEmpty()) {
      throw new IllegalArgumentException("environment must contain " + key);
    }
    return requireNonBlank(env.get(key), key);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }
}
