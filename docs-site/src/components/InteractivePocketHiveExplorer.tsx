import React, { useEffect, useId, useState } from "react";

type PlaneFocus = "all" | "control" | "work";

type ArchitectureDetailKey =
  | "orchestrator"
  | "controller"
  | "control-queues"
  | "source-worker"
  | "work-queue"
  | "action-worker"
  | "sink-worker"
  | "evidence"
  | "multi-swarm"
  | "redis";

type ArchitectureDetail = {
  title: string;
  description: string;
};

type RedisStep = {
  title: string;
  kind: "dataset" | "worker";
  actor: string;
  description: string;
};

type LabelledSectionProps = {
  labelledBy?: string;
};

const ARCHITECTURE_DETAILS: Record<ArchitectureDetailKey, ArchitectureDetail> = {
  orchestrator: {
    title: "Orchestrator",
    description:
      "Creates swarms, sends lifecycle commands to controllers, and routes targeted live configuration directly to workers.",
  },
  controller: {
    title: "Swarm Controller",
    description:
      "Applies startup configuration, fans out swarm-wide lifecycle, and reports swarm-level outcomes.",
  },
  "control-queues": {
    title: "RabbitMQ control queues",
    description:
      "Carry commands, status, and outcomes. Business WorkItems do not pass through the control plane.",
  },
  "source-worker": {
    title: "Source worker",
    description:
      "Creates or reads a WorkItem and publishes it to the route declared by the scenario.",
  },
  "work-queue": {
    title: "RabbitMQ work queue",
    description:
      "Decouples worker stages while carrying WorkItems, including their payload and routing context.",
  },
  "action-worker": {
    title: "Action worker",
    description:
      "Runs the scenario behavior, such as a protocol call, transformation, or business operation.",
  },
  "sink-worker": {
    title: "Sink worker",
    description:
      "Consumes the final WorkItem, publishes the result, and emits completion evidence.",
  },
  evidence: {
    title: "Runtime evidence",
    description:
      "Use Journal, logs, metrics, and Grafana to confirm what ran and whether the swarm converged.",
  },
  "multi-swarm": {
    title: "Independent swarms",
    description:
      "Each swarm owns its controller, queues, lifecycle, workers, and evidence, even when swarms collaborate.",
  },
  redis: {
    title: "Optional Redis hand-off",
    description:
      "Redis can hold shared dataset state for a scenario; it is not required for PocketHive lifecycle control.",
  },
};

const MULTI_SWARM_REDIS_STEPS: RedisStep[] = [
  {
    title: "Ready",
    kind: "dataset",
    actor: "Redis dataset state",
    description:
      "A record waits in Ready until Swarm A can process it.",
  },
  {
    title: "Auth",
    kind: "worker",
    actor: "Swarm A workers",
    description:
      "Swarm A consumes the record and runs the authorization behavior defined by its scenario.",
  },
  {
    title: "TopUpNeeded",
    kind: "dataset",
    actor: "Redis dataset state",
    description:
      "Swarm A writes a record that needs more balance to TopUpNeeded.",
  },
  {
    title: "TopUp",
    kind: "worker",
    actor: "Swarm B workers",
    description:
      "Swarm B consumes the record and runs its independently controlled top-up behavior.",
  },
  {
    title: "Ready",
    kind: "dataset",
    actor: "Redis dataset state",
    description:
      "Swarm B returns the updated record to Ready and completes the optional loop.",
  },
];

const ONE_SWARM_REDIS_STEPS: RedisStep[] = MULTI_SWARM_REDIS_STEPS.map((step) => {
  if (step.title === "TopUp") {
    return {
      ...step,
      actor: "Same swarm - top-up workers",
      description:
        "A top-up worker in the same swarm consumes the record and runs the next scenario behavior.",
    };
  }

  return {
    ...step,
    actor: step.actor === "Swarm A workers" ? "Same swarm - auth workers" : step.actor,
    description: step.description
      .replace(/Swarm A/g, "The swarm")
      .replace(/Swarm B/g, "The top-up worker"),
  };
});

const LIFECYCLE_STEPS = [
  "Create",
  "READY",
  "Start",
  "RUNNING",
  "Stop",
  "STOPPED",
  "Remove",
];

function usePrefersReducedMotion(): boolean {
  const [prefersReducedMotion, setPrefersReducedMotion] = useState(false);

  useEffect(() => {
    const query = window.matchMedia("(prefers-reduced-motion: reduce)");
    const updatePreference = () => setPrefersReducedMotion(query.matches);

    updatePreference();
    query.addEventListener("change", updatePreference);
    return () => query.removeEventListener("change", updatePreference);
  }, []);

  return prefersReducedMotion;
}

function OptionalViewButton({
  label,
  description,
  pressed,
  onClick,
}: {
  label: string;
  description: string;
  pressed: boolean;
  onClick: () => void;
}): React.JSX.Element {
  return (
    <button
      type="button"
      className="iph-option-card"
      aria-label={label}
      aria-pressed={pressed}
      onClick={onClick}
    >
      <span>
        <strong>{label}</strong>
        <small>{description}</small>
      </span>
      <span className="iph-option-state" aria-hidden="true">
        {pressed ? "On" : "Off"}
      </span>
    </button>
  );
}

function FocusButton({
  label,
  value,
  focus,
  onChange,
}: {
  label: string;
  value: PlaneFocus;
  focus: PlaneFocus;
  onChange: (value: PlaneFocus) => void;
}): React.JSX.Element {
  return (
    <button
      type="button"
      aria-pressed={focus === value}
      onClick={() => onChange(value)}
    >
      {label}
    </button>
  );
}

function DetailNode({
  detailKey,
  title,
  description,
  selectedDetail,
  onSelect,
  className,
}: {
  detailKey: ArchitectureDetailKey;
  title: string;
  description: string;
  selectedDetail: ArchitectureDetailKey;
  onSelect: (detail: ArchitectureDetailKey) => void;
  className?: string;
}): React.JSX.Element {
  return (
    <button
      type="button"
      className={"iph-node" + (className ? " " + className : "")}
      aria-pressed={selectedDetail === detailKey}
      onClick={() => onSelect(detailKey)}
    >
      <strong>{title}</strong>
      <span>{description}</span>
    </button>
  );
}

function WorkQueueButton({
  selectedDetail,
  onSelect,
}: {
  selectedDetail: ArchitectureDetailKey;
  onSelect: (detail: ArchitectureDetailKey) => void;
}): React.JSX.Element {
  return (
    <button
      type="button"
      className="iph-route-queue"
      aria-label="RabbitMQ work queue"
      aria-pressed={selectedDetail === "work-queue"}
      onClick={() => onSelect("work-queue")}
    >
      <span aria-hidden="true">&rarr;</span>
      <small>
        RabbitMQ
        <br />
        queue
      </small>
      <span aria-hidden="true">&rarr;</span>
    </button>
  );
}

function ArchitectureLegend(): React.JSX.Element {
  return (
    <ul className="iph-legend" aria-label="Architecture legend">
      <li>
        <span className="iph-legend-mark iph-legend-mark--control" aria-hidden="true" />
        <span><strong>Control</strong> - commands and status</span>
      </li>
      <li>
        <span className="iph-legend-mark iph-legend-mark--work" aria-hidden="true" />
        <span><strong>Work</strong> - WorkItems between workers</span>
      </li>
      <li>
        <span className="iph-legend-mark iph-legend-mark--redis" aria-hidden="true" />
        <span><strong>Optional Redis</strong> - dataset state</span>
      </li>
      <li>
        <span className="iph-legend-mark iph-legend-mark--evidence" aria-hidden="true" />
        <span><strong>Evidence</strong> - Journal, logs, metrics, Grafana</span>
      </li>
    </ul>
  );
}

function SwarmArchitecture({
  label,
  focus,
  selectedDetail,
  onSelect,
}: {
  label: string;
  focus: PlaneFocus;
  selectedDetail: ArchitectureDetailKey;
  onSelect: (detail: ArchitectureDetailKey) => void;
}): React.JSX.Element {
  const controlClass =
    focus === "work" ? "is-muted" : focus === "control" ? "is-focused" : "";
  const workClass =
    focus === "control" ? "is-muted" : focus === "work" ? "is-focused" : "";

  return (
    <article className="iph-swarm-card" aria-label={label + " architecture"}>
      <header className="iph-swarm-title">
        <span>{label}</span>
        <small>isolated runtime</small>
      </header>

      <section className={"iph-plane-panel iph-plane-panel--control " + controlClass}>
        <span className="iph-plane-label">Control plane</span>
        <div className="iph-node-row">
          <DetailNode
            detailKey="controller"
            title="Swarm Controller"
            description="starts workers; fans out lifecycle"
            selectedDetail={selectedDetail}
            onSelect={onSelect}
          />
          <span className="iph-direction" aria-hidden="true">&harr;</span>
          <DetailNode
            detailKey="control-queues"
            title="RabbitMQ control"
            description="commands, status, outcomes"
            selectedDetail={selectedDetail}
            onSelect={onSelect}
          />
        </div>
      </section>

      <section className={"iph-plane-panel iph-plane-panel--work " + workClass}>
        <span className="iph-plane-label">Scenario worker route</span>
        <div className="iph-worker-route" aria-label="Example scenario-defined worker route">
          <DetailNode
            detailKey="source-worker"
            title="Source"
            description="creates a WorkItem"
            selectedDetail={selectedDetail}
            onSelect={onSelect}
            className="iph-node--worker"
          />
          <WorkQueueButton selectedDetail={selectedDetail} onSelect={onSelect} />
          <DetailNode
            detailKey="action-worker"
            title="Action"
            description="runs scenario behavior"
            selectedDetail={selectedDetail}
            onSelect={onSelect}
            className="iph-node--worker"
          />
          <WorkQueueButton selectedDetail={selectedDetail} onSelect={onSelect} />
          <DetailNode
            detailKey="sink-worker"
            title="Sink"
            description="publishes the result"
            selectedDetail={selectedDetail}
            onSelect={onSelect}
            className="iph-node--worker"
          />
        </div>
      </section>

      <button
        type="button"
        className="iph-evidence-strip"
        aria-pressed={selectedDetail === "evidence"}
        onClick={() => onSelect("evidence")}
      >
        <strong>Evidence</strong>
        <span>Journal &middot; logs &middot; metrics &middot; Grafana</span>
      </button>
    </article>
  );
}

export function ArchitectureExplorer({
  labelledBy,
}: LabelledSectionProps): React.JSX.Element {
  const [showSecondSwarm, setShowSecondSwarm] = useState(false);
  const [showRedis, setShowRedis] = useState(false);
  const [planeFocus, setPlaneFocus] = useState<PlaneFocus>("all");
  const [selectedDetail, setSelectedDetail] =
    useState<ArchitectureDetailKey>("orchestrator");
  const focusLabelId = useId();
  const selected = ARCHITECTURE_DETAILS[selectedDetail];

  const toggleSecondSwarm = () => {
    setShowSecondSwarm((current) => {
      if (current) {
        setShowRedis(false);
        setSelectedDetail((detail) =>
          detail === "multi-swarm" || detail === "redis"
            ? "orchestrator"
            : detail,
        );
      } else {
        setSelectedDetail("multi-swarm");
      }
      return !current;
    });
  };

  const toggleRedis = () => {
    setShowRedis((current) => {
      if (!current) {
        setShowSecondSwarm(true);
        setSelectedDetail("redis");
      } else {
        setSelectedDetail((detail) =>
          detail === "redis" ? "multi-swarm" : detail,
        );
      }
      return !current;
    });
  };

  const viewSummary = [
    showSecondSwarm ? "two independent swarms" : "one swarm",
    planeFocus === "all" ? "both planes" : planeFocus + " plane focused",
    showRedis ? "optional Redis visible" : "optional Redis hidden",
  ].join("; ");

  return (
    <section
      className="iph-card iph-card--architecture"
      aria-labelledby={labelledBy}
      aria-label={labelledBy ? undefined : "System map"}
    >
      <div className="iph-card-lead">
        <p className="iph-eyebrow">Explore the current system</p>
        <p>
          Orchestrator coordinates isolated swarms over RabbitMQ. WorkItems move
          worker-to-worker; evidence confirms outcomes.
        </p>
      </div>

      <div className="iph-architecture-controls">
        <div className="iph-option-group">
          <span className="iph-control-label">Optional views</span>
          <div className="iph-option-grid">
            <OptionalViewButton
              label="Second swarm"
              description="Independent runtime"
              pressed={showSecondSwarm}
              onClick={toggleSecondSwarm}
            />
            <OptionalViewButton
              label="Optional Redis"
              description="Shared dataset state"
              pressed={showRedis}
              onClick={toggleRedis}
            />
          </div>
        </div>

        <div className="iph-focus-control">
          <span id={focusLabelId} className="iph-control-label">Focus</span>
          <div className="iph-segmented" role="group" aria-labelledby={focusLabelId}>
            <FocusButton label="All" value="all" focus={planeFocus} onChange={setPlaneFocus} />
            <FocusButton label="Control" value="control" focus={planeFocus} onChange={setPlaneFocus} />
            <FocusButton label="Work" value="work" focus={planeFocus} onChange={setPlaneFocus} />
          </div>
        </div>
      </div>

      <p className="iph-visually-hidden" aria-live="polite">
        Current view: {viewSummary}.
      </p>

      <ArchitectureLegend />

      <button
        type="button"
        className="iph-orchestrator-node"
        aria-pressed={selectedDetail === "orchestrator"}
        onClick={() => setSelectedDetail("orchestrator")}
      >
        <span className="iph-plane-label">Global control owner</span>
        <strong>Orchestrator</strong>
        <span>lifecycle &rarr; controller &middot; live config &rarr; worker</span>
      </button>
      <div className="iph-control-connection" aria-label="Control-plane connection over RabbitMQ">
        <span aria-hidden="true">&darr;</span>
        <strong>RabbitMQ control plane</strong>
        <span aria-hidden="true">&darr;</span>
      </div>

      <div className={"iph-swarm-grid" + (showSecondSwarm ? "" : " is-single")}>
        <SwarmArchitecture
          label="Swarm A"
          focus={planeFocus}
          selectedDetail={selectedDetail}
          onSelect={setSelectedDetail}
        />
        {showSecondSwarm ? (
          <SwarmArchitecture
            label="Swarm B"
            focus={planeFocus}
            selectedDetail={selectedDetail}
            onSelect={setSelectedDetail}
          />
        ) : null}
      </div>

      <p className="iph-architecture-note">
        <strong>Boundary:</strong> startup and swarm-wide lifecycle go through the
        controller; targeted live configuration goes directly to workers; business
        WorkItems stay on the work plane.
        {showSecondSwarm ? (
          <span> Each swarm keeps its own controller, queues, lifecycle, and evidence.</span>
        ) : null}
      </p>

      {showRedis ? (
        <div className="iph-redis-bridge">
          <span className="iph-plane-label">Optional Redis dataset hand-off</span>
          <strong>Swarm A &rarr; TopUpNeeded &middot; Swarm B &rarr; Ready</strong>
          <p>Dataset state only; Redis does not control the swarm lifecycle.</p>
        </div>
      ) : null}

      <div className="iph-detail-panel" aria-live="polite" aria-atomic="true">
        <span>Selected component</span>
        <strong>{selected.title}</strong>
        <p>{selected.description}</p>
      </div>
    </section>
  );
}

function RedisSequence({
  routeTitle,
  labelledBy,
  steps,
}: {
  routeTitle: string;
  labelledBy?: string;
  steps: RedisStep[];
}): React.JSX.Element {
  const [activeStep, setActiveStep] = useState(0);
  const [isPlaying, setIsPlaying] = useState(false);
  const prefersReducedMotion = usePrefersReducedMotion();

  useEffect(() => {
    if (prefersReducedMotion) {
      setIsPlaying(false);
    }
  }, [prefersReducedMotion]);

  useEffect(() => {
    if (!isPlaying || prefersReducedMotion) {
      return;
    }

    const timer = window.setInterval(() => {
      setActiveStep((current) => (current + 1) % steps.length);
    }, 2200);
    return () => window.clearInterval(timer);
  }, [isPlaying, prefersReducedMotion, steps.length]);

  const selectStep = (step: number) => {
    setIsPlaying(false);
    setActiveStep(step);
  };

  const step = steps[activeStep];

  return (
    <section
      className="iph-card iph-card--sequence"
      aria-labelledby={labelledBy}
      aria-label={labelledBy ? undefined : "Optional Redis collaboration"}
    >
      <div className="iph-card-lead iph-card-lead--split">
        <div>
          <p className="iph-eyebrow">Optional worked example</p>
          <strong className="iph-route-title">{routeTitle}</strong>
        </div>
        <p>Redis is one possible dataset hand-off, not a required component.</p>
      </div>

      <ol className="iph-step-track" aria-label="Five-step optional Redis collaboration loop">
        {steps.map((redisStep, index) => (
          <li key={redisStep.title + "-" + index}>
            <button
              type="button"
              className={"iph-sequence-step iph-sequence-step--" + redisStep.kind}
              aria-pressed={activeStep === index}
              onClick={() => selectStep(index)}
            >
              <span>Step {index + 1}</span>
              <strong>{redisStep.title}</strong>
              <small>{redisStep.actor}</small>
            </button>
          </li>
        ))}
      </ol>

      <div className="iph-step-detail" aria-live="polite" aria-atomic="true">
        <div>
          <span>Step {activeStep + 1} of {steps.length}</span>
          <strong>{step.title}</strong>
          <p>{step.description}</p>
        </div>
        <strong>{step.actor}</strong>
      </div>

      <div className="iph-playback" aria-label="Optional Redis playback controls">
        <button
          type="button"
          disabled={activeStep === 0}
          onClick={() => selectStep(activeStep - 1)}
        >
          Previous
        </button>
        <span>Step {activeStep + 1} of {steps.length}</span>
        <button
          type="button"
          disabled={activeStep === steps.length - 1}
          onClick={() => selectStep(activeStep + 1)}
        >
          Next
        </button>
        <button
          type="button"
          disabled={prefersReducedMotion}
          aria-label="Automatic playback"
          aria-pressed={isPlaying}
          onClick={() => setIsPlaying((current) => !current)}
          title={
            prefersReducedMotion
              ? "Playback is disabled by your reduced-motion preference"
              : undefined
          }
        >
          {prefersReducedMotion ? "Playback unavailable" : isPlaying ? "Pause" : "Play"}
        </button>
      </div>
    </section>
  );
}

export function OptionalRedisCollaboration({
  labelledBy,
}: LabelledSectionProps): React.JSX.Element {
  return (
    <RedisSequence
      routeTitle="Ready -> Auth -> TopUpNeeded -> TopUp -> Ready"
      labelledBy={labelledBy}
      steps={MULTI_SWARM_REDIS_STEPS}
    />
  );
}

export function LifecycleStrip({
  labelledBy,
}: LabelledSectionProps): React.JSX.Element {
  return (
    <section
      className="iph-card iph-lifecycle"
      aria-labelledby={labelledBy}
      aria-label={labelledBy ? undefined : "Lifecycle and evidence"}
    >
      <div className="iph-card-lead iph-card-lead--split">
        <div>
          <p className="iph-eyebrow">Proof, not just requests</p>
          <strong className="iph-route-title">Follow the state to completion</strong>
        </div>
        <p>Acceptance starts verification; runtime evidence finishes it.</p>
      </div>

      <ol className="iph-lifecycle-strip" aria-label="Swarm lifecycle">
        {LIFECYCLE_STEPS.map((lifecycleStep) => (
          <li key={lifecycleStep}>{lifecycleStep}</li>
        ))}
      </ol>

      <div className="iph-proof-grid">
        <div>
          <strong>1 &middot; Accepted</strong>
          <span>The UI or API accepted the action.</span>
        </div>
        <div>
          <strong>2 &middot; Dispatched</strong>
          <span>The command reached the intended swarm.</span>
        </div>
        <div>
          <strong>3 &middot; Converged</strong>
          <span>Status and evidence confirm the expected state.</span>
        </div>
      </div>
    </section>
  );
}

export function CoreConceptsExplorer(): React.JSX.Element {
  return <ArchitectureExplorer />;
}

export function RedisBasicLoopExplorer(): React.JSX.Element {
  return (
    <RedisSequence
      routeTitle="Ready -> Auth -> TopUpNeeded -> TopUp -> Ready"
      labelledBy="one-swarm-loop"
      steps={ONE_SWARM_REDIS_STEPS}
    />
  );
}

export function RedisMultiSwarmExplorer(): React.JSX.Element {
  return (
    <RedisSequence
      routeTitle="Ready -> Auth -> TopUpNeeded -> TopUp -> Ready"
      labelledBy="multi-swarm-hand-off"
      steps={MULTI_SWARM_REDIS_STEPS}
    />
  );
}

export default function InteractivePocketHiveExplorer(): React.JSX.Element {
  return (
    <div className="iph-wrap">
      <ArchitectureExplorer />
      <OptionalRedisCollaboration />
      <LifecycleStrip />
    </div>
  );
}
