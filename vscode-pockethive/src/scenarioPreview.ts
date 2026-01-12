import { ScenarioDetail } from './types';

export function renderScenarioPreviewHtml(scenario: ScenarioDetail): string {
  const bees = scenario.template?.bees ?? [];
  const title = escapeHtml(scenario.name ?? scenario.id ?? 'Scenario preview');
  const description = scenario.description ? escapeHtml(scenario.description) : '';
  const controllerImage = scenario.template?.image ? escapeHtml(scenario.template.image) : 'unknown';
  const beeCards = bees.length
    ? bees
        .map((bee) => {
          const role = escapeHtml(bee.role ?? 'unknown');
          const image = escapeHtml(bee.image ?? 'unknown');
          const input = escapeHtml(bee.work?.in ?? '-');
          const output = escapeHtml(bee.work?.out ?? '-');
          return `
            <div class="bee-card">
              <div class="bee-title">${role}</div>
              <div class="bee-row"><span>image</span><strong>${image}</strong></div>
              <div class="bee-row"><span>in</span><strong>${input}</strong></div>
              <div class="bee-row"><span>out</span><strong>${output}</strong></div>
            </div>
          `;
        })
        .join('')
    : '<div class="empty">No bees declared.</div>';

  return `<!DOCTYPE html>
  <html lang="en">
    <head>
      <meta charset="UTF-8">
      <meta name="viewport" content="width=device-width, initial-scale=1.0">
      <style>
        :root {
          color-scheme: light dark;
        }
        body {
          margin: 0;
          padding: 20px;
          font-family: var(--vscode-font-family);
          color: var(--vscode-foreground);
          background: var(--vscode-editor-background);
        }
        h1 {
          font-size: 20px;
          margin: 0 0 6px 0;
        }
        .muted {
          color: var(--vscode-descriptionForeground);
          margin-bottom: 16px;
        }
        .summary {
          display: grid;
          grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
          gap: 12px;
          margin-bottom: 16px;
        }
        .summary-card {
          border: 1px solid var(--vscode-panel-border);
          border-radius: 8px;
          padding: 12px;
          background: var(--vscode-editorWidget-background);
        }
        .summary-card span {
          display: block;
          font-size: 11px;
          text-transform: uppercase;
          letter-spacing: 0.04em;
          color: var(--vscode-descriptionForeground);
          margin-bottom: 6px;
        }
        .bee-grid {
          display: grid;
          grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
          gap: 12px;
        }
        .bee-card {
          border: 1px solid var(--vscode-panel-border);
          border-radius: 10px;
          padding: 12px;
          background: var(--vscode-editorWidget-background);
        }
        .bee-title {
          font-size: 14px;
          font-weight: 600;
          margin-bottom: 8px;
        }
        .bee-row {
          display: flex;
          justify-content: space-between;
          font-size: 12px;
          padding: 4px 0;
          border-top: 1px solid var(--vscode-panel-border);
        }
        .bee-row span {
          color: var(--vscode-descriptionForeground);
        }
        .empty {
          padding: 12px;
          border: 1px dashed var(--vscode-panel-border);
          border-radius: 8px;
          color: var(--vscode-descriptionForeground);
        }
      </style>
    </head>
    <body>
      <h1>${title}</h1>
      <div class="muted">${description}</div>
      <div class="summary">
        <div class="summary-card">
          <span>Scenario id</span>
          <div>${escapeHtml(scenario.id ?? 'unknown')}</div>
        </div>
        <div class="summary-card">
          <span>Controller image</span>
          <div>${controllerImage}</div>
        </div>
        <div class="summary-card">
          <span>Bees</span>
          <div>${bees.length}</div>
        </div>
      </div>
      <h2>Bees</h2>
      <div class="bee-grid">
        ${beeCards}
      </div>
    </body>
  </html>`;
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
