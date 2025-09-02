import { type Config } from 'tailwindcss'

export default {
  darkMode: ['class'],
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'Segoe UI', 'Roboto', 'Helvetica', 'Arial', 'Apple Color Emoji', 'Segoe UI Emoji'],
        mono: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'Monaco', 'Consolas', 'Liberation Mono', 'Courier New']
      },
      colors: {
        ph: {
          bg: '#0b1020',
          surface: '#0f1629',
          card: '#121b31',
          border: 'rgba(255,255,255,0.06)',
          text: '#e5e7eb',
          muted: '#9aa4b2',
          accent: '#f59e0b',
          accent2: '#22d3ee',
          link: '#93c5fd'
        },
        phl: {
          bg: '#f8fafc',
          surface: '#eef2f7',
          card: '#ffffff',
          border: 'rgba(0,0,0,0.08)',
          text: '#0b1020',
          muted: '#475569',
          accent: '#f59e0b',
          accent2: '#22d3ee',
          link: '#1d4ed8'
        }
      },
      borderRadius: {
        xl: '16px',
        '2xl': '20px'
      },
      boxShadow: {
        ph: '0 6px 20px rgba(0,0,0,0.35)'
      }
    }
  },
  plugins: []
} satisfies Config
