import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
  },
  // ADR-003 跨端边界纪律(可检查的硬线):逻辑/状态/展示层平台无关,
  // 禁止引用任何平台 IO(fetch/EventSource/WebSocket/XMLHttpRequest/wx.*);
  // 网络/流 IO 必须收进 src/api/ 适配层(Taro 迁移时只换它)。
  // 仅 src/api/** 与测试豁免。
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/api/**', 'src/test/**', '**/*.test.{ts,tsx}', '**/*.spec.{ts,tsx}'],
    rules: {
      'no-restricted-globals': [
        'error',
        { name: 'fetch', message: '平台 IO 禁入逻辑/状态层(ADR-003 边界);走 src/api/ 适配层。' },
        { name: 'EventSource', message: '平台 IO 禁入逻辑/状态层(ADR-003 边界);走 src/api/ 适配层。' },
        { name: 'WebSocket', message: '平台 IO 禁入逻辑/状态层(ADR-003 边界);走 src/api/ 适配层。' },
        { name: 'XMLHttpRequest', message: '平台 IO 禁入逻辑/状态层(ADR-003 边界);走 src/api/ 适配层。' },
      ],
      'no-restricted-syntax': [
        'error',
        {
          selector: "MemberExpression[object.name='wx']",
          message: '小程序 API 禁入逻辑/状态层(ADR-003);Phase 1 不写小程序代码,迁移期只在 api/ 适配层接 wx.*。',
        },
      ],
    },
  },
])
