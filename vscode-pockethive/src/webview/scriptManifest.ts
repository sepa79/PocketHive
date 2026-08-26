/**
 * Responsibility: Define the ordered runtime modules required by the companion webview.
 * Must not: Load scripts or duplicate HTML composition behavior.
 * Contract: Every production and test webview host loads this exact ordered manifest.
 */
export const WEBVIEW_SCRIPT_FILES = Object.freeze([
  'eventFilters.js',
  'debugEvidence.js',
  'scenarioViews.js',
  'hiveViews.js',
  'debugViews.js',
  'eventViews.js',
  'environmentViews.js',
  'main.js',
] as const);
