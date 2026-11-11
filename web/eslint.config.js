import js from '@eslint/js';
import ts from 'typescript-eslint';
import svelte from 'eslint-plugin-svelte';
import prettier from 'eslint-config-prettier';
import globals from 'globals';

export default ts.config(
  {
    ignores: [
      'node_modules/**',
      '.svelte-kit/**',
      'build/**',
      'src/lib/generated/**',
      '.impeccable/**',
    ],
  },
  js.configs.recommended,
  ...ts.configs.recommended,
  ...svelte.configs['flat/recommended'],
  { languageOptions: { globals: { ...globals.browser, ...globals.node } } },
  {
    files: ['**/*.svelte', '**/*.svelte.ts'],
    languageOptions: { parserOptions: { parser: ts.parser } },
  },
  // 静态部署挂在源站根路径；媒体 API 链接保持根相对路径。
  { rules: { 'svelte/no-navigation-without-resolve': 'off' } },
  prettier,
);
