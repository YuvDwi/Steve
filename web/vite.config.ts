import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Vite dev server proxies /events and /command to the embedded Steve
// dashboard server (started in Minecraft via /steve dashboard).
// The mod server is bound to 127.0.0.1:8765 by default.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/events':  {target: 'http://127.0.0.1:8765', changeOrigin: false, ws: false},
      '/command': {target: 'http://127.0.0.1:8765', changeOrigin: false},
      '/chat':    {target: 'http://127.0.0.1:8765', changeOrigin: false},
      '/plan':    {target: 'http://127.0.0.1:8765', changeOrigin: false},
    },
  },
});
