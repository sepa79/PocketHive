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
        'ph-bg': '#0f1116',
        'ph-surface': 'rgba(12,16,22,0.7)',
        'ph-card': 'rgba(12,16,22,0.66)',
        'ph-border': 'rgba(255,255,255,0.12)',
        'ph-text': '#ffffff',
        'ph-muted': '#9aa0a6',
        'ph-accent': '#f59e0b',
        'ph-accent2': '#33e1ff',
        'ph-link': '#93c5fd',
        'phl-bg': '#f8fafc',
        'phl-surface': '#eef2f7',
        'phl-card': '#ffffff',
        'phl-border': 'rgba(0,0,0,0.08)',
        'phl-text': '#0b1020',
        'phl-muted': '#475569',
        'phl-accent': '#f59e0b',
        'phl-accent2': '#33e1ff',
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
