import { defineConfig } from 'vite';

export default defineConfig({
  root: 'app',
  base: '/luckystar/',
  server: {
    host: '0.0.0.0',
    port: 4173,
  },
  build: {
    outDir: '../dist',
    emptyOutDir: true,
  },
});
