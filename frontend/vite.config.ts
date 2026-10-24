import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts: true,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      '/actuator': { target: 'http://127.0.0.1:8080', changeOrigin: false },
    },
  },
  preview: { host: '0.0.0.0', port: 4173 },
  build: { target: 'es2022', sourcemap: false },
})
