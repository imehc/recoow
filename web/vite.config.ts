import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';
import { defineConfig, loadEnv, type ProxyOptions } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'RECOOW_');
  const target = process.env.RECOOW_API_URL || env.RECOOW_API_URL || 'http://127.0.0.1:8080';
  const base = process.env.RECOOW_WEB_BASE ?? env.RECOOW_WEB_BASE ?? '';
  if (base && !/^\/(?:[a-zA-Z0-9_-]+\/)*[a-zA-Z0-9_-]+$/.test(base)) {
    throw new Error(
      'RECOOW_WEB_BASE must be empty or a path like /recoow, without a trailing slash.',
    );
  }
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
      sveltekit({
        adapter: adapter({ fallback: 'index.html' }),
        preprocess: vitePreprocess(),
        paths: { base: base as '' | `/${string}` },
      }),
    ],
    server: { proxy },
    preview: { proxy },
  };
});
