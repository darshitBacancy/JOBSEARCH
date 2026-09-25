import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// All AI calls go React -> Spring Boot -> OpenRouter. The browser never talks to OpenRouter.
// Override the backend location with BACKEND_URL (env var or .env.local), default http://localhost:9091.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  const backendUrl = env.BACKEND_URL || 'http://localhost:9091';
  return {
    plugins: [react()],
    server: {
      port: 4000,
      strictPort: true,
      proxy: {
        '/api': {
          target: backendUrl,
          changeOrigin: true,
        },
      },
    },
  };
});
