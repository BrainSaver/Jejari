import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 개발: npm run dev → http://localhost:5173, /api는 Spring Boot(8080)로 넘긴다 (같은 출처처럼 동작 → CORS·쿠키 설정 불필요)
// 배포: npm run build → dist/. Gradle processResources가 dist를 jar의 static/으로 복사한다 (build.gradle)
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
