import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'path';

// Two roots: the widget itself (loaded inside an iframe) and a merchant demo
// page that embeds the widget via the loader script. Build emits both.
export default defineConfig({
  plugins: [react()],
  server: {
    // Bind to all interfaces so the demo is reachable from a phone on the same
    // Wi-Fi (e.g. http://<your-LAN-ip>:5173/demo.html), not just localhost.
    host: true,
    port: 5173,
    strictPort: true,
    // Proxy engine API calls to the FlexPop engine on :8080. This lets a phone
    // reach the engine through this dev server when a host firewall (e.g. an
    // app-aware corporate agent like Sophos) allows inbound to node/Vite but
    // blocks inbound to the java engine on :8080. The phone hits :5173 (allowed)
    // and Vite forwards to localhost:8080 over loopback (unfiltered).
    proxy: {
      '/v1': { target: 'http://localhost:8080', changeOrigin: true },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: true },
    },
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
