package io.pockethive.swarmcontroller;

import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.TrafficPolicy;

import java.util.Map;
import java.util.Optional;

/**
 * Contract describing the high-level lifecycle hooks the swarm controller exposes to the orchestrator.
 * <p>
 * Implementations, such as {@link SwarmLifecycleManager}, translate these calls into concrete
 * container orchestration steps (creating RabbitMQ resources, starting worker containers, emitting
 * control-plane signals, and tracking readiness). The methods below map directly to the control
 * buttons described in {@code docs/ORCHESTRATOR-REST.md}; use that document alongside the comments
 * here when onboarding to the control plane.
 */
public interface SwarmLifecycle {

  /**
   * Load an upcoming swarm plan so the controller can warm any dependent resources without enabling
   * workloads yet.
   * <p>
   * <strong>When it runs:</strong> invoked before {@link #start(String)} whenever a new template is
   * chosen. The orchestrator provides a JSON swarm template payload (see
   * {@code docs/ARCHITECTURE.md#swarm-plans}) so queues, exchanges, and containers can be
   * pre-created.
   * <p>
   * <strong>Caller responsibilities:</strong> supply valid JSON matching {@link SwarmPlan}. Example:
   * <pre>{@code
   * {
   *   "swarmId": "demo",
   *   "bees": [
   *     {"role": "generator", "image": "pockethive/generator:latest"}
   *   ]
   * }
   * }</pre>
   * Implementations should not enable work here; they may persist the plan for reuse by
   * {@link #start(String)}.
   */
  void prepare(String templateJson);

  /**
   * Launch or resume the swarm using the provided plan, enabling queues and starting containers as
   * needed.
   * <p>
   * <strong>When it runs:</strong> issued after {@link #prepare(String)} once operators confirm the
   * swarm configuration. Receives the serialized plan that was previously prepared.
   * <p>
   * <strong>Caller responsibilities:</strong> the caller should expect idempotent behaviour; calling
   * {@code start} repeatedly with the same plan should simply ensure workloads are enabled.
   * Implementations should ensure correlation information and control acknowledgements are emitted so
   * the orchestrator can mark the swarm {@code RUNNING}.
   */
  void start(String planJson);

  /**
   * Pause the swarm by disabling workloads but leaving infrastructure running.
   * <p>
   * <strong>When it runs:</strong> triggered by the control plane when operators issue
   * {@code POST /api/swarms/{id}/stop}. Implementations should emit a {@code ready.swarm-stop}
   * confirmation (see {@code docs/ORCHESTRATOR-REST.md}).
   * <p>
   * <strong>Caller responsibilities:</strong> ensure subsequent calls to {@link #start(String)} or
   * {@link #enableAll()} re-enable work as desired; stopping does not tear down queues/containers.
   */
  void stop();

  /**
   * Tear down all swarm resources, deleting queues and removing any controller-managed containers.
   * <p>
   * <strong>When it runs:</strong> called when an operator issues {@code POST /api/swarms/{id}/remove}
   * after a stop. Implementations should emit a {@code ready.swarm-remove} confirmation and clean up
   * any cached plan data.
   * <p>
   * <strong>Caller responsibilities:</strong> ensure no further {@link #start(String)} calls occur
   * until {@link #prepare(String)} is invoked with a fresh plan.
   */
  void remove();

  /**
   * Report the current high-level lifecycle status for health dashboards.
   * <p>
   * Typical statuses include {@code STOPPED}, {@code RUNNING}, or {@code REMOVED}. Callers should use
   * this alongside {@link #getMetrics()} to populate /api/swarms/{id} responses.
   */
  SwarmStatus getStatus();

  /**
   * Record that a worker instance has completed its readiness handshake.
   * <p>
   * <strong>When it runs:</strong> invoked when the controller receives a {@code ready.swarm-start}
   * event from a worker (for example, routing key {@code ev.ready.generator.generator-1}).
   * <p>
   * <strong>Return value:</strong> {@code true} when all expected workers are ready and healthy,
   * signalling the orchestrator can emit a ready confirmation.
   */
  boolean markReady(String role, String instance);

  /**
   * Update the latest heartbeat timestamp for a worker instance.
   * <p>
   * <strong>When it runs:</strong> on every {@code heartbeat.*} control message. Implementations use
   * this to expire stale workers after the controller's configured time-to-live window (15 seconds by
   * default in {@link SwarmLifecycleManager}).
   * <p>
   * <strong>Caller responsibilities:</strong> provide the role (e.g. {@code "processor"}) and the
   * instance identifier (e.g. {@code "processor-1"}).
   */
  void updateHeartbeat(String role, String instance);

  /**
   * Track whether a worker instance has acknowledged its enabled/disabled state.
   * <p>
   * <strong>When it runs:</strong> after a {@code config-update} command succeeds, each worker echoes
   * the flag back, and the controller stores it so {@link #getMetrics()} can report running counts.
   */
  void updateEnabled(String role, String instance, boolean enabled);

  /**
   * Summarise swarm health for dashboards and the REST API.
   * <p>
   * The returned {@link SwarmMetrics} combines desired vs. healthy workers, currently enabled counts,
   * and the oldest heartbeat watermark so callers can render UI health badges.
   */
  SwarmMetrics getMetrics();

  /**
   * Capture a snapshot of RabbitMQ queue statistics for the active swarm.
   * <p>
   * Implementations should report message depth, consumer counts, and optionally the age (seconds) of
   * the oldest message when the broker exposes that metric. Callers can merge this data into the
   * {@code queueStats} envelope published by the control plane.
   *
   * @return a map keyed by fully-qualified queue name (e.g. {@code ph.demo.work.in}).
   */
  Map<String, QueueStats> snapshotQueueStats();

  /**
   * Enable all workloads.
   * <p>
   * Implementations should publish a {@code config-update} command with {@code enabled=true} so
   * workers resume processing messages.
  */
  void enableAll();

  /**
   * Convenience toggle invoked by REST endpoints to flip swarm workloads on or off.
   * <p>
   * <strong>When it runs:</strong> triggered by {@code POST /api/swarm-managers/{id}/enabled} calls.
   * Implementations should emit {@code config-update} signals and update
   * {@link #getStatus()} accordingly.
   *
   * @param enabled whether workloads should actively process messages ({@code true}) or pause ({@code false}).
   */
  void setSwarmEnabled(boolean enabled);

  /**
   * Determine whether the swarm has received all readiness signals required to safely enable work.
   * <p>
   * Implementations should return {@code true} when the controller has already observed every
   * expected worker heartbeat (or when no workers are expected) so callers can emit the
   * {@code ready.swarm-template} confirmation without racing the control-plane queue declarations.
   */
  boolean isReadyForWork();

  /**
   * Returns the traffic policy from the currently prepared plan, if any.
   * <p>
   * Default implementations return {@code null}; concrete lifecycle managers can override to surface
   * config details for status payloads and UI capabilities.
   */
  default TrafficPolicy trafficPolicy() {
    return null;
  }

  default Optional<String> handleConfigUpdateError(String role, String instance, String error) {
    return Optional.empty();
  }

  default void fail(String reason) {
  }

  /**
   * Indicates whether any bootstrap config payloads are still waiting to be published.
   */
  default boolean hasPendingConfigUpdates() {
    return false;
  }
}
