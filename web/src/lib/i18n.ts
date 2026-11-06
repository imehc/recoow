import { get } from 'svelte/store';
import { addMessages, init, format } from 'svelte-i18n';
import zh from './locales/zh.json';
import en from './locales/en.json';

export type MessageKey = keyof typeof zh;
export type MessageValues = Record<string, string | number | boolean | Date | null | undefined>;
const english: Record<MessageKey, string> = en;
addMessages('zh', zh);
addMessages('en', english);
void init({ fallbackLocale: 'zh', initialLocale: 'zh' });

export function translate(key: MessageKey, locale: 'zh' | 'en', values?: MessageValues): string {
  return get(format)({ id: key, locale, values, default: zh[key] });
}
