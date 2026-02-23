import React, { useEffect, useMemo, useState } from "react";

type Detail = {
  title: string;
  summary: string;
  bullets: string[];
};

const DETAILS: Record<string, Detail> = {
  orchestrator: {
    title: "Orchestrator",
    summary: "Global control owner for PocketHive.",
    bullets: [
      "Creates and manages swarms.",
      "Sends lifecycle and config commands.",
      "Collects swarm-level outcomes.",
    ],
  },
  "swarm-a": {
    title: "Swarm A",
    summary: "One runtime group for a scenario flow.",
    bullets: [
      "Runs a dedicated worker chain.",
      "Uses control and work queues.",
      "Can run independently from other swarms.",
    ],
  },
  "swarm-b": {
    title: "Swarm B",
    summary: "Second runtime group that can cooperate with Swarm A.",
    bullets: [
      "Can process another stage of the same dataset.",
      "Can run different rates and profiles.",
      "Keeps the same control-plane model.",
    ],
  },
  "controller-a": {
    title: "Swarm Controller (A)",
    summary: "Applies plan and tracks worker status in Swarm A.",
    bullets: [
      "Manages local worker lifecycle.",
      "Keeps status snapshots up to date.",
      "Bridges control and work planes.",
    ],
  },
  "controller-b": {
    title: "Swarm Controller (B)",
    summary: "Applies plan and tracks worker status in Swarm B.",
    bullets: [
      "Same responsibilities as Controller A.",
      "Operates independently for this swarm.",
      "Reports outcomes and metrics upstream.",
    ],
  },
  "control-queues-a": {
    title: "Control Queues (A)",
    summary: "Control-plane transport for commands and status in Swarm A.",
    bullets: [
      "Carries start/stop/config/status-request commands.",
      "Carries outcomes and status events.",
      "Keeps control plane responsive even when workload is paused.",
    ],
  },
  "control-queues-b": {
    title: "Control Queues (B)",
    summary: "Control-plane transport for commands and status in Swarm B.",
    bullets: [
      "Same semantics as in Swarm A.",
      "Independent command/status channel per swarm.",
      "Supports isolated swarm operation.",
    ],
  },
  "work-queues-a": {
    title: "Work Queues (A)",
    summary: "Asynchronous handoff in Swarm A work plane.",
    bullets: [
      "Decouples producer and consumer speed.",
      "Carries WorkItems across stages.",
      "Buffers bursts safely.",
    ],
  },
  "work-queues-b": {
    title: "Work Queues (B)",
    summary: "Asynchronous handoff in Swarm B work plane.",
    bullets: [
      "Supports independent throughput.",
      "Improves stage isolation.",
      "Feeds downstream workers in order.",
    ],
  },
  "workitem-a": {
    title: "WorkItem (A)",
    summary: "Canonical business payload envelope in Swarm A.",
    bullets: [
      "Carries payload and metadata together.",
      "Flows through all work stages.",
      "Keeps context consistent end-to-end.",
    ],
  },
  "workitem-b": {
    title: "WorkItem (B)",
    summary: "Canonical business payload envelope in Swarm B.",
    bullets: [
      "Same envelope model as in Swarm A.",
      "Supports independent pipeline processing.",
      "Can be correlated across stages.",
    ],
  },
  "worker-input-a": {
    title: "Input Worker (A)",
    summary: "Entry stage in work pipeline for Swarm A.",
    bullets: [
      "Consumes from source queue.",
      "Normalizes or enriches incoming payloads.",
      "Forwards WorkItem to processing stage.",
    ],
  },
  "worker-processing-a": {
    title: "Processing Worker (A)",
    summary: "Middle stage executing business operation in Swarm A.",
    bullets: [
      "Runs core transaction logic.",
      "Typically invokes HTTP/REST or other protocols.",
      "Sends result to output stage.",
    ],
  },
  "worker-output-a": {
    title: "Output Worker (A)",
    summary: "Final stage in work pipeline for Swarm A.",
    bullets: [
      "Publishes transformed/output payloads.",
      "Updates downstream destinations.",
      "Emits completion-oriented metrics/status.",
    ],
  },
  "worker-input-b": {
    title: "Input Worker (B)",
    summary: "Entry stage in work pipeline for Swarm B.",
    bullets: [
      "Consumes from source queue.",
      "Prepares WorkItems for processing.",
      "Forwards to processing stage.",
    ],
  },
  "worker-processing-b": {
    title: "Processing Worker (B)",
    summary: "Middle stage executing business operation in Swarm B.",
    bullets: [
      "Runs main transaction step.",
      "Can call REST/protocol endpoints.",
      "Forwards processed WorkItems.",
    ],
  },
  "worker-output-b": {
    title: "Output Worker (B)",
    summary: "Final stage in work pipeline for Swarm B.",
    bullets: [
      "Publishes final payload.",
      "Routes output to next state/destination.",
      "Provides output-side metrics and signals.",
    ],
  },
};

const BASIC_LOOP_STEPS = [
  {
    id: "ready",
    title: "Ready",
    kind: "dataset",
    desc: "Record is available in Redis list Ready.",
  },
  {
    id: "auth",
    title: "Auth Txn",
    kind: "worker",
    desc: "A worker performs the operation and validates state.",
  },
  {
    id: "need",
    title: "TopUpNeeded",
    kind: "dataset",
    desc: "If top-up is needed, record is moved to TopUpNeeded.",
  },
  {
    id: "topup",
    title: "Topup Txn",
    kind: "worker",
    desc: "A worker performs top-up operation and writes record back to Ready.",
  },
];

const CROSS_SWARM_STEPS = [
  {
    id: "ready-visible",
    title: "Step 1",
    desc: "Ready is visible on both lanes: as source for Swarm A and as target state for Swarm B.",
    aActive: ["from", "wait"],
    bActive: ["to", "wait"],
  },
  {
    id: "a-auth",
    title: "Step 2",
    desc: "Swarm A runs Auth Txn while Swarm B waits for TopUpNeeded.",
    aActive: ["op"],
    bActive: ["wait"],
  },
  {
    id: "topup-visible",
    title: "Step 3",
    desc: "TopUpNeeded is visible on both lanes while Swarm A waits for Ready and Swarm B waits for TopUpNeeded.",
    aActive: ["to", "wait"],
    bActive: ["from", "wait"],
  },
  {
    id: "b-topup",
    title: "Step 4",
    desc: "Swarm B runs Topup Txn while Swarm A waits for Ready.",
    aActive: ["wait"],
    bActive: ["op"],
  },
  {
    id: "ready-back",
    title: "Step 5",
    desc: "Ready becomes visible again on both lanes.",
    aActive: ["from", "wait"],
    bActive: ["to", "wait"],
  },
];

function SwarmCard({
  swarmId,
  activeNodeId,
  onNodeClick,
}: {
  swarmId: "a" | "b";
  activeNodeId: string;
  onNodeClick: (id: string) => void;
}): React.JSX.Element {
  const title = swarmId === "a" ? "Swarm A" : "Swarm B";
  const roleSuffix = swarmId === "a" ? "A" : "B";
  return (
    <article className="iph-swarm">
      <header className="iph-swarm-head">
        <button
          type="button"
          className={`iph-chip ${activeNodeId === `swarm-${swarmId}` ? "is-active" : ""}`}
          onClick={() => onNodeClick(`swarm-${swarmId}`)}
        >
          {title}
        </button>
        <span className="iph-badge">Runtime</span>
      </header>

      <div className="iph-subcards">
        <section className="iph-subcard iph-subcard-control">
          <div className="iph-plane iph-control">Control Plane</div>
          <div className="iph-row">
            <button
              type="button"
              className={`iph-chip ${activeNodeId === `controller-${swarmId}` ? "is-active" : ""}`}
              onClick={() => onNodeClick(`controller-${swarmId}`)}
            >
              Swarm Controller ({roleSuffix})
            </button>
            <button
              type="button"
              className={`iph-chip ${activeNodeId === `control-queues-${swarmId}` ? "is-active" : ""}`}
              onClick={() => onNodeClick(`control-queues-${swarmId}`)}
            >
              Control Queues ({roleSuffix})
            </button>
          </div>
        </section>

        <section className="iph-subcard iph-subcard-work">
          <div className="iph-plane iph-work">Work Plane</div>
          <div className="iph-row">
            <button
              type="button"
              className={`iph-chip ${activeNodeId === `work-queues-${swarmId}` ? "is-active" : ""}`}
              onClick={() => onNodeClick(`work-queues-${swarmId}`)}
            >
              Work Queues ({roleSuffix})
            </button>
            <button
              type="button"
              className={`iph-chip ${activeNodeId === `workitem-${swarmId}` ? "is-active" : ""}`}
              onClick={() => onNodeClick(`workitem-${swarmId}`)}
            >
              WorkItem ({roleSuffix})
            </button>
          </div>
          <div className="iph-row iph-workers-row">
            <button
              type="button"
              className={`iph-chip iph-chip-worker ${activeNodeId === `worker-input-${swarmId}` ? "is-active" : ""}`}
              onClick={() => onNodeClick(`worker-input-${swarmId}`)}
            >
              Input ({roleSuffix})
            </button>
            <span className="iph-arrow iph-worker-arrow">-&gt;</span>
            <button
              type="button"
              className={`iph-chip iph-chip-worker ${activeNodeId === `worker-processing-${swarmId}` ? "is-active" : ""}`}
              onClick={() => onNodeClick(`worker-processing-${swarmId}`)}
            >
              Processing ({roleSuffix})
            </button>
            <span className="iph-arrow iph-worker-arrow">-&gt;</span>
            <button
              type="button"
              className={`iph-chip iph-chip-worker ${activeNodeId === `worker-output-${swarmId}` ? "is-active" : ""}`}
              onClick={() => onNodeClick(`worker-output-${swarmId}`)}
            >
              Output ({roleSuffix})
            </button>
          </div>
        </section>
      </div>
    </article>
  );
}

function TwoSwarmLane({
  title,
  from,
  op,
  to,
  activeKeys,
  waitingText,
  isActive,
}: {
  title: string;
  from: string;
  op: string;
  to: string;
  activeKeys: Array<"from" | "op" | "to" | "wait" | "idle">;
  waitingText?: string;
  isActive?: boolean;
}): React.JSX.Element {
  const isKeyActive = (key: "from" | "op" | "to" | "wait" | "idle") =>
    activeKeys.includes(key);

  return (
    <article className={`iph-flow-card ${isActive ? "is-active" : ""}`}>
      <h3>{title}</h3>
      <div className="iph-flow-line">
        <span className={`iph-flow-pill iph-flow-redis ${isKeyActive("from") ? "is-active" : ""}`}>
          <span className="iph-flow-label">Dataset</span>
          <span className="iph-flow-value">{from}</span>
        </span>
        <span className={`iph-arrow ${isKeyActive("from") || isKeyActive("op") ? "is-active" : ""}`}>-&gt;</span>
        <span className={`iph-flow-pill iph-flow-op ${isKeyActive("op") ? "is-active" : ""}`}>
          <span className="iph-flow-label">Workers</span>
          <span className="iph-flow-value">{op}</span>
        </span>
        <span className={`iph-arrow ${isKeyActive("op") || isKeyActive("to") ? "is-active" : ""}`}>-&gt;</span>
        <span className={`iph-flow-pill iph-flow-redis ${isKeyActive("to") ? "is-active" : ""}`}>
          <span className="iph-flow-label">Dataset</span>
          <span className="iph-flow-value">{to}</span>
        </span>
      </div>
      {waitingText ? (
        <div className={`iph-wait ${isKeyActive("wait") ? "is-active" : ""}`}>
          {waitingText}
        </div>
      ) : null}
    </article>
  );
}

export function CoreConceptsExplorer(): React.JSX.Element {
  const [activeNodeId, setActiveNodeId] = useState("orchestrator");
  const activeDetail = useMemo(
    () => DETAILS[activeNodeId] ?? DETAILS.orchestrator,
    [activeNodeId],
  );

  return (
    <section className="iph-card">
      <h2>Core Concepts Explorer</h2>
      <p className="iph-subtitle">
        Click cards to inspect responsibilities in the Orchestrator -&gt; Swarm A/B
        topology.
      </p>

      <div className="iph-orchestrator-block">
        <button
          type="button"
          className={`iph-chip iph-orchestrator ${activeNodeId === "orchestrator" ? "is-active" : ""}`}
          onClick={() => setActiveNodeId("orchestrator")}
        >
          Orchestrator
        </button>
      </div>

      <div className="iph-links-row" aria-hidden="true">
        <span className="iph-link-column">
          <span className="iph-link-line" />
          <span className="iph-link-arrow">↓</span>
        </span>
        <span className="iph-link-column">
          <span className="iph-link-line" />
          <span className="iph-link-arrow">↓</span>
        </span>
      </div>

      <div className="iph-swarms-grid">
        <SwarmCard swarmId="a" activeNodeId={activeNodeId} onNodeClick={setActiveNodeId} />
        <SwarmCard swarmId="b" activeNodeId={activeNodeId} onNodeClick={setActiveNodeId} />
      </div>

      <div className="iph-detail">
        <h3>{activeDetail.title}</h3>
        <p>{activeDetail.summary}</p>
        <ul>
          {activeDetail.bullets.map((bullet) => (
            <li key={bullet}>{bullet}</li>
          ))}
        </ul>
      </div>
    </section>
  );
}

export function RedisBasicLoopExplorer(): React.JSX.Element {
  const [basicStep, setBasicStep] = useState(0);
  const [autoplayBasic, setAutoplayBasic] = useState(true);

  useEffect(() => {
    if (!autoplayBasic) {
      return;
    }
    const timer = setInterval(() => {
      setBasicStep((prev) => (prev + 1) % BASIC_LOOP_STEPS.length);
    }, 1800);
    return () => clearInterval(timer);
  }, [autoplayBasic]);

  return (
    <section className="iph-card">
      <div className="iph-card-head">
        <h2>Basic Redis Loop</h2>
        <button type="button" onClick={() => setAutoplayBasic((v) => !v)}>
          {autoplayBasic ? "Pause autoplay" : "Start autoplay"}
        </button>
      </div>
      <p className="iph-subtitle">
        Redis acts as a dataset database. Workers perform operations and move records
        between state lists.
      </p>

      <div className="iph-loop">
        {BASIC_LOOP_STEPS.map((step, index) => {
          const isActive = index === basicStep;
          const isRedis = step.kind === "dataset";
          return (
            <React.Fragment key={step.id}>
              <button
                type="button"
                className={`iph-step ${isActive ? "is-active" : ""} ${isRedis ? "iph-step-redis" : "iph-step-worker"}`}
                onClick={() => setBasicStep(index)}
              >
                <span className="iph-step-label">{isRedis ? "Dataset" : "Workers"}</span>
                <span className="iph-step-value">{step.title}</span>
              </button>
              {index < BASIC_LOOP_STEPS.length - 1 ? (
                <span className={`iph-arrow ${isActive ? "is-active" : ""}`}>-&gt;</span>
              ) : null}
            </React.Fragment>
          );
        })}
        <span className="iph-arrow iph-arrow-loop">-&gt;</span>
      </div>

      <div className="iph-detail">
        <h3>{BASIC_LOOP_STEPS[basicStep].title}</h3>
        <p>{BASIC_LOOP_STEPS[basicStep].desc}</p>
      </div>
    </section>
  );
}

export function RedisMultiSwarmExplorer(): React.JSX.Element {
  const [crossStep, setCrossStep] = useState(0);
  const [autoplayCross, setAutoplayCross] = useState(true);

  useEffect(() => {
    if (!autoplayCross) {
      return;
    }
    const timer = setInterval(() => {
      setCrossStep((prev) => (prev + 1) % CROSS_SWARM_STEPS.length);
    }, 2200);
    return () => clearInterval(timer);
  }, [autoplayCross]);

  const cross = CROSS_SWARM_STEPS[crossStep];
  const laneAKeys = cross.aActive as Array<"from" | "op" | "to" | "wait" | "idle">;
  const laneBKeys = cross.bActive as Array<"from" | "op" | "to" | "wait" | "idle">;

  return (
    <section className="iph-card">
      <div className="iph-card-head">
        <h2>Two-Swarm Redis Collaboration</h2>
        <button type="button" onClick={() => setAutoplayCross((v) => !v)}>
          {autoplayCross ? "Pause autoplay" : "Start autoplay"}
        </button>
      </div>
      <p className="iph-subtitle">
        Swarm A writes to TopUpNeeded. Swarm B waits for records there, runs its
        operation, then writes back to Ready.
      </p>

      <div className="iph-two-swarm-grid">
        <TwoSwarmLane
          title="Swarm A"
          from="Ready"
          op="Auth Txn"
          to="TopUpNeeded"
          activeKeys={laneAKeys}
          waitingText="Waiting for records on Ready"
          isActive={laneAKeys.length > 0 && !laneAKeys.includes("idle")}
        />
        <TwoSwarmLane
          title="Swarm B"
          from="TopUpNeeded"
          op="Topup Txn"
          to="Ready"
          activeKeys={laneBKeys}
          waitingText="Waiting for records on TopUpNeeded"
          isActive={laneBKeys.length > 0 && !laneBKeys.includes("idle")}
        />
      </div>

      <div className="iph-detail">
        <h3>{cross.title}</h3>
        <p>{cross.desc}</p>
      </div>
    </section>
  );
}

export default function InteractivePocketHiveExplorer(): React.JSX.Element {
  return (
    <div className="iph-wrap">
      <CoreConceptsExplorer />
      <RedisBasicLoopExplorer />
      <RedisMultiSwarmExplorer />
    </div>
  );
}
