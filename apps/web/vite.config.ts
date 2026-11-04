import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Send API calls to the Spring Boot API on :8080 so the browser sees one origin:
    // the HttpOnly refresh cookie then works without CORS.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
