# UI v2 — Shell & Flow (WIP)

This document is the single place for **UI v2 navigation/layout decisions**.

## Goals

- Predictable UX on **1920×1080** (Full HD).
- Stable layout (no “panel unmount flicker” when navigating).
- Fully linkable screens (URL is the state; no query params for navigation).
- A clean foundation for:
  - Scenario Browser + Editor
  - Hive (swarm management)
  - Journal
  - Login / users / permissions

## Global layout (Shell)

UI v2 is an “IDE-like” shell:

- **TopBar** (fixed height, **full width**)
  - left: **Logo** (click → Home) + **Breadcrumb**
  - middle: context toolbar area (per-view actions; optional)
  - right: **Connectivity indicator** (HAL Eye) + **Help** (Monolith) + **Login/User menu**
- **Left SideNav** (flat list)
  - **icons only** + tooltip (no nested expansion in the nav)
  - switches **sections**: Home, Hive, Journal, Scenarios, Other
  - **collapsible**
    - default: collapsed on 1080p, expanded on >1080p
    - user choice persisted in browser session storage
- **Content area**
  - section-specific layouts (2 or 3 columns), but the shell remains mounted

## Routing rules

- Use **path-based routes** (no query params for navigation state).
- `Back` must always work (browser history semantics).
- Every screen must be fully linkable/shareable via its URL.

Example:
- `/v2/scenarios`
- `/v2/scenarios/:scenarioId`
- `/v2/scenarios/:scenarioId/edit`

## Sections

### Home

Purpose:
- welcome screen
- links to documentation
- MOTD (from a file / endpoint later)
- quick tiles: Scenarios / Hive / Journal / Connectivity
- login entry (also available in TopBar)

Suggested subpages:
- `/v2/health` (also accessible from HAL Eye)

### Scenarios

Two-stage flow:

1) **Browser / Viewer** (read-only)
   - left panel: scenario tree (supports subdirectories)
   - main panel: “Overview” (description, metadata, components summary)
   - optional right panel or tab: YAML preview + bundle file viewer (RO)

2) **Editor**
   - left panel: bundle files (only for the selected scenario)
   - main panel: YAML editor (SSOT)
   - tabs: Diff / Swarm / Plan / Templates
   - top toolbar actions: Save, Undo/Redo, Validate, Reload

The 2-column vs 3-column variant is a UX choice to validate in practice.

### Hive

Purpose:
- swarm list (“table”)
- swarm details
- optional graph (React Flow)
- health/debug views

Within Hive use tabs/panels for internal navigation, not SideNav expansion.

### Journal

Purpose:
- current runs
- history with filtering/search

### Other

Purpose:
- perf calculator and other utilities

## TopBar global controls

### Connectivity indicator (HAL Eye)

Single global status icon which merges “connect” + health:

- green: connected (STOMP) and healthy
- blue blinking: connecting / retry in progress
- orange blinking: disconnected (but auto-retry continues)
- red blinking: health check indicates problems

Click → `/v2/health`:
- details (health, connection state, last error)
- manual actions (reconnect, update credentials)
- auto-retry is on by default (no need to “keep clicking”)

### Help (Monolith)

Global help entry:

- context help (short, per-screen)
- link to general docs (initially via external links; optional Markdown viewer later)
