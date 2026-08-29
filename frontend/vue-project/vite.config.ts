import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 3000,
    proxy: {
      // '/api/files': {
      //   target: 'http://localhost:11082',
      //   changeOrigin: true
      // },
      // '/api/storage': {
      //   target: 'http://localhost:11082',
      //   changeOrigin: true
      // },
      // '/api/v2/storage': {
      //   target: 'http://localhost:11082',
      //   changeOrigin: true
      // },
      // '/api/recycle': {
      //   target: 'http://localhost:11082',
      //   changeOrigin: true
      // },
      // '/api/share': {
      //   target: 'http://localhost:11083',
      //   changeOrigin: true
      // },
      // '/ai/stream/chat': {
      //   target: 'http://localhost:11080',
      //   changeOrigin: true
      // },
      // '/api/auth': {
      //   target: 'http://localhost:8081',
      //   changeOrigin: true
      // },

      '/api/': {
        target: 'http://localhost:17000',
        changeOrigin: true
      },
    }
  }
})
