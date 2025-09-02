import type { Config } from 'tailwindcss';

export default {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    colors: {
      background: 'var(--color-background)',
      card: 'var(--color-card)',
      accent: 'var(--color-accent)',
      text: 'var(--color-text)',
    },
  },
  plugins: [],
} satisfies Config;
