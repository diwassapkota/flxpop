import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'path';

// Two roots: the widget itself (loaded inside an iframe) and a merchant demo
// page that embeds the widget via the loader script. Build emits both.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      input: {
        widget: resolve(__dirname, 'index.html'),
        demo:   resolve(__dirname, 'demo.html'),
      },
    },
  },
});
