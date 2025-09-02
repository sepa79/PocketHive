export default {
  darkMode: ['class'],
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'Segoe UI', 'Roboto', 'Helvetica', 'Arial', 'Apple Color Emoji', 'Segoe UI Emoji'],
        mono: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'Monaco', 'Consolas', 'Liberation Mono', 'Courier New'],
      },
      colors: {
        'ph-bg': '#0b1020',
        'ph-surface': '#0f1629',
        'ph-card': '#121b31',
        'ph-border': 'rgba(255,255,255,0.06)',
        'ph-text': '#e5e7eb',
        'ph-muted': '#9aa4b2',
        'ph-accent': '#f59e0b',
        'ph-accent2': '#22d3ee',
        'ph-link': '#93c5fd',
        'phl-bg': '#f8fafc',
        'phl-surface': '#eef2f7',
        'phl-card': '#ffffff',
        'phl-border': 'rgba(0,0,0,0.08)',
        'phl-text': '#0b1020',
        'phl-muted': '#475569',
        'phl-accent': '#f59e0b',
        'phl-accent2': '#22d3ee',
        'phl-link': '#1d4ed8',
      },
      borderRadius: {
        xl: '16px',
        '2xl': '20px',
      },
      boxShadow: {
        ph: '0 6px 20px rgba(0,0,0,0.35)',
      },
    },
  },
  plugins: [],
};
