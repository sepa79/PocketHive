import type { SidebarsConfig } from "@docusaurus/plugin-content-docs";

const sidebars: SidebarsConfig = {
  customerSidebar: [
    {
      type: "doc",
      id: "guides/presentation/interactive-pockethive-overview",
      label: "PocketHive overview",
    },
    {
      type: "category",
      label: "1. Start",
      collapsed: false,
      items: [
        {
          type: "doc",
          id: "guides/onboarding/start-here",
          label: "Choose your path",
        },
        {
          type: "doc",
          id: "guides/ui/application-guide",
          label: "Explore the application",
        },
      ],
    },
    {
      type: "category",
      label: "2. Evaluate and deploy",
      collapsed: false,
      items: [
        {
          type: "doc",
          id: "guides/operators/deployment",
          label: "Choose a deployment path",
        },
        {
          type: "doc",
          id: "guides/onboarding/quickstart-15min",
          label: "Run locally from source",
        },
      ],
    },
    {
      type: "category",
      label: "3. Run and troubleshoot",
      collapsed: false,
      items: [
        {
          type: "doc",
          id: "guides/operators/swarm-lifecycle",
          label: "Control a swarm",
        },
        {
          type: "doc",
          id: "guides/operators/observability-troubleshooting",
          label: "Verify and troubleshoot",
        },
      ],
    },
    {
      type: "category",
      label: "4. Build and automate",
      collapsed: false,
      items: [
        {
          type: "doc",
          id: "guides/integrations/pockethive-mcp-and-bundles",
          label: "Connect MCP and bundles",
        },
        {
          type: "doc",
          id: "guides/onboarding/first-scenario",
          label: "Create a scenario",
        },
      ],
    },
    {
      type: "category",
      label: "5. Understand the system",
      collapsed: false,
      items: [
        {
          type: "doc",
          id: "guides/concepts/system-workflows",
          label: "Understand workflows",
        },
        {
          type: "doc",
          id: "guides/examples/redis-dataset-patterns",
          label: "Use optional Redis",
        },
      ],
    },
    {
      type: "category",
      label: "Reference",
      items: [
        { type: "ref", id: "GLOSSARY", label: "Glossary" },
        { type: "ref", id: "PROJECT_MAP", label: "Project map" },
        { type: "ref", id: "ARCHITECTURE", label: "Architecture" },
      ],
    },
  ],

  referenceSidebar: [
    {
      type: "ref",
      id: "guides/presentation/interactive-pockethive-overview",
      label: "← Customer guide",
    },
    {
      type: "category",
      label: "Customer and operator reference",
      items: [
        { type: "doc", id: "GLOSSARY", label: "Glossary" },
        { type: "doc", id: "UPGRADING", label: "Upgrading" },
        {
          type: "doc",
          id: "guides/integrations/authoring-and-test-tools",
          label: "Authoring and test tools",
        },
      ],
    },
    {
      type: "category",
      label: "Scenario reference",
      items: [
        { type: "doc", id: "scenarios/README", label: "Scenario overview" },
        {
          type: "doc",
          id: "scenarios/SCENARIO_PLAN_GUIDE",
          label: "Scenario plans",
        },
        {
          type: "doc",
          id: "scenarios/SCENARIO_PATTERNS",
          label: "Scenario patterns",
        },
        {
          type: "doc",
          id: "scenarios/SCENARIO_VARIABLES",
          label: "Scenario variables",
        },
        {
          type: "doc",
          id: "scenarios/SCENARIO_BUNDLE_DIAGNOSTICS",
          label: "Bundle diagnostics",
        },
        {
          type: "doc",
          id: "guides/workers-basics",
          label: "Worker basics",
        },
        {
          type: "doc",
          id: "guides/workers-advanced",
          label: "Worker advanced",
        },
        {
          type: "doc",
          id: "guides/templating-basics",
          label: "Templating basics",
        },
        {
          type: "doc",
          id: "guides/templating-advanced",
          label: "Templating advanced",
        },
      ],
    },
    {
      type: "category",
      label: "Extension reference",
      items: [
        {
          type: "doc",
          id: "sdk/worker-sdk-quickstart",
          label: "Worker SDK",
        },
      ],
    },
    {
      type: "category",
      label: "Architecture and contracts",
      items: [
        { type: "doc", id: "PROJECT_MAP", label: "Project map" },
        { type: "doc", id: "ARCHITECTURE", label: "Architecture" },
        {
          type: "doc",
          id: "architecture/workerCapabilities",
          label: "Worker capabilities",
        },
        {
          type: "doc",
          id: "scenarios/SCENARIO_CONTRACT",
          label: "Scenario contract",
        },
        {
          type: "doc",
          id: "scenarios/SCENARIO_BUNDLE_WORKSPACE_API_SPEC",
          label: "Scenario workspace API",
        },
        {
          type: "doc",
          id: "ORCHESTRATOR-REST",
          label: "Orchestrator REST",
        },
        { type: "doc", id: "USAGE", label: "Usage" },
        { type: "doc", id: "HIVEFORGE", label: "HiveForge" },
        { type: "doc", id: "observability", label: "Observability" },
        {
          type: "doc",
          id: "correlation-vs-idempotency",
          label: "Correlation and idempotency",
        },
      ],
    },
    {
      type: "category",
      label: "Documentation maintenance",
      items: [
        {
          type: "doc",
          id: "guides/ui/screenshot-evidence",
          label: "Screenshot evidence",
        },
      ],
    },
  ],
};

export default sidebars;
