import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'

// Quinoa builds this into dist/ and serves it from the Quarkus app; in `quarkus dev` it proxies the Vite dev server.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  // Must match quarkus.quinoa.dev-server.port; strict so Quinoa never forwards to another app's dev server.
  server: { port: 5188, strictPort: true },
})
