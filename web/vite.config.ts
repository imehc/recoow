import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vite';

const proxy = {
  '/api': { target: process.env.RECOOW_API_URL ?? 'http://127.0.0.1:8080', changeOrigin: false },
};

export default defineConfig({
  plugins: [
    tailwindcss(),
    sveltekit({ adapter: adapter({ fallback: 'index.html' }), preprocess: vitePreprocess() }),
  ],
  server: { proxy },
  preview: { proxy },
});
