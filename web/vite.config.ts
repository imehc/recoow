import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';
import { defineConfig, loadEnv, type ProxyOptions } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'RECOOW_');
  const target = process.env.RECOOW_API_URL || env.RECOOW_API_URL || 'http://127.0.0.1:8080';
  const proxy: Record<string, ProxyOptions> = {
    '/api': {
      target,
      changeOrigin: false,
      configure(proxy) {
        proxy.on('error', () => {
          console.error(`[recoow] /api 代理无法连接 ${target}，请确认 Go 后端已启动。`);
        });
      },
    },
  };
  return {
    plugins: [
      tailwindcss(),
      sveltekit({ adapter: adapter({ fallback: 'index.html' }), preprocess: vitePreprocess() }),
    ],
    server: { proxy },
    preview: { proxy },
  };
});
