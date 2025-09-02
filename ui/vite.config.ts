import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@legacy': path.resolve(__dirname, '../UI-Legacy'),
      '@legacyAssets': path.resolve(__dirname, '../UI-Legacy/assets'),
    },
  },
  server: {
    fs: {
      allow: ['..'],
    },
  },
});
