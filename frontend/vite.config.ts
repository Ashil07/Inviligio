/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    // In development the browser talks only to Vite; /api is forwarded to Spring Boot,
    // so no CORS is involved locally. Production sets VITE_API_BASE_URL instead.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    // Form tests type with user-event and take a few seconds each; with many files in parallel on
    // a busy machine they can pass the default 5 s, so allow more. Real failures still fail.
    testTimeout: 15_000,
  },
})
