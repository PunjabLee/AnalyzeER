import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// M0 dev proxy: frontend calls /api/*, proxied to SpringBoot backend on :8080
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
