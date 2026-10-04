import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Browser calls /api/... on :5173, Vite forwards to Spring on :8080. No CORS needed.
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})