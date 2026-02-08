import type { Config } from "@docusaurus/types";
import type * as Preset from "@docusaurus/preset-classic";
import { themes as prismThemes } from "prism-react-renderer";

const DOCS_URL = process.env.DOCS_URL || "http://localhost:3000";
const DOCS_BASE_URL = process.env.DOCS_BASE_URL || "/";

const config: Config = {
  title: "PocketHive Docs",
  tagline: "RabbitMQ-centric load and behavior simulator",
  url: DOCS_URL,
  baseUrl: DOCS_BASE_URL,
  onBrokenLinks: "throw",
  onBrokenMarkdownLinks: "throw",
  favicon: "img/favicon.svg",

  organizationName: "pockethive",
  projectName: "PocketHive",

  i18n: {
    defaultLocale: "en",
    locales: ["en"],
  },

  markdown: {
    mermaid: true,
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
            "scenarios/**",
            "sdk/**",
            "control-plane/worker-guide.md",
            "ARCHITECTURE.md",
            "USAGE.md",
            "ORCHESTRATOR-REST.md",
            "observability.md",
            "correlation-vs-idempotency.md",
          ],
          exclude: ["archive/**", "inProgress/**", "**/*.html"],
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
      title: "PocketHive",
      items: [
        { to: "/", label: "Docs", position: "left" },
        { to: "/", label: "Start here", position: "left" },
      ],
    },
    footer: {
      style: "dark",
      links: [
        {
          title: "Docs",
          items: [
            { label: "Start here", to: "/" },
            { label: "Quickstart", to: "/guides/onboarding/quickstart-15min" },
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
