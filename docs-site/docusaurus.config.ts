import type { Config } from "@docusaurus/types";
import type * as Preset from "@docusaurus/preset-classic";
import { themes as prismThemes } from "prism-react-renderer";

const DOCS_URL = process.env.DOCS_URL || "http://localhost:3000";
const DOCS_BASE_URL = process.env.DOCS_BASE_URL || "/";
const POCKETHIVE_APP_URL = process.env.POCKETHIVE_APP_URL;
const docsLink = (path = "") => `${DOCS_BASE_URL}${path}`;

const config: Config = {
  title: "PocketHive Docs",
  tagline: "Build, run, and observe reusable behavior simulations",
  url: DOCS_URL,
  baseUrl: DOCS_BASE_URL,
  onBrokenLinks: "throw",
  favicon: "img/favicon.svg",

  organizationName: "pockethive",
  projectName: "PocketHive",

  i18n: {
    defaultLocale: "en",
    locales: ["en"],
  },

  markdown: {
    mermaid: true,
    hooks: {
      onBrokenMarkdownLinks: "throw",
    },
  },
  themes: ["@docusaurus/theme-mermaid"],

  presets: [
    [
      "classic",
      {
        docs: {
          path: "../docs",
          routeBasePath: "/",
          sidebarPath: require.resolve("./sidebars.ts"),
          include: [
            "guides/**",
            "architecture/**",
            "scenarios/**",
            "sdk/**",
            "control-plane/worker-guide.md",
            "ARCHITECTURE.md",
            "USAGE.md",
            "ORCHESTRATOR-REST.md",
            "HIVEFORGE.md",
            "PROJECT_MAP.md",
            "GLOSSARY.md",
            "UPGRADING.md",
            "observability.md",
            "correlation-vs-idempotency.md",
          ],
          exclude: [
            "archive/**",
            "inProgress/**",
            "scenarios/CAPACITY_MODELER_NOTES.md",
            "scenarios/SCENARIO_BUILDER_NOTES.md",
            "**/*.html",
          ],
        },
        blog: false,
        theme: {
          customCss: require.resolve("./src/css/custom.css"),
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    navbar: {
      logo: {
        alt: "PocketHive Logo",
        src: "img/logo.svg",
      },
      items: [
        {
          type: "docSidebar",
          sidebarId: "customerSidebar",
          label: "Customer guide",
          position: "left",
        },
        {
          type: "docSidebar",
          sidebarId: "referenceSidebar",
          label: "Reference",
          position: "left",
        },
        ...(POCKETHIVE_APP_URL
          ? [
              {
                label: "PocketHive app",
                href: POCKETHIVE_APP_URL,
                target: "_blank" as const,
                position: "right" as const,
              },
            ]
          : []),
      ],
    },
    footer: {
      style: "dark",
      links: [
        {
          title: "Docs",
          items: [
            { label: "Overview", to: docsLink() },
            {
              label: "Quickstart",
              to: docsLink("guides/onboarding/quickstart-15min"),
            },
            {
              label: "Application guide",
              to: docsLink("guides/ui/application-guide"),
            },
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} PocketHive`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ["bash", "json", "java", "yaml"],
    },
  },
};

export default config;
