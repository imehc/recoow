import { mode, setMode, userPrefersMode } from 'mode-watcher';
import { translate, type MessageKey, type MessageValues } from './i18n';

export type Theme = 'system' | 'light' | 'dark';
export type Language = 'system' | 'zh' | 'en';

export function resolveLanguage(choice: Language, languages: readonly string[]): 'zh' | 'en' {
  return choice === 'system'
    ? languages[0]?.toLowerCase().startsWith('zh')
      ? 'zh'
      : 'en'
    : choice;
}

class Preferences {
  language = $state<Language>('system');
  browserLanguages = $state<readonly string[]>(['zh']);
  locale = $derived(resolveLanguage(this.language, this.browserLanguages));
  get theme(): Theme {
    return userPrefersMode.current;
  }
  get dark() {
    return mode.current === 'dark';
  }

  setTheme(theme: Theme) {
    setMode(theme);
  }
  setLanguage(language: Language) {
    this.language = language;
    this.persist();
  }

  private applyLanguage() {
    document.documentElement.lang = this.locale === 'zh' ? 'zh-CN' : 'en';
  }

  private persist() {
    try {
      localStorage.setItem('recoow:preferences', JSON.stringify({ language: this.language }));
    } catch {
      // 存储可能被禁用。
    }
    this.applyLanguage();
  }

  start() {
    this.browserLanguages = navigator.languages;
    try {
      const saved = JSON.parse(localStorage.getItem('recoow:preferences') ?? '{}');
      if (['system', 'zh', 'en'].includes(saved.language)) this.language = saved.language;
      // 保留在改用 mode-watcher 之前已保存的偏好。
      if (['system', 'light', 'dark'].includes(saved.theme)) setMode(saved.theme);
    } catch {
      // 忽略无效或不可用的偏好。
    }
    this.persist();
    const languageChanged = () => {
      this.browserLanguages = navigator.languages;
      this.applyLanguage();
    };
    window.addEventListener('languagechange', languageChanged);
    return () => window.removeEventListener('languagechange', languageChanged);
  }
}

export const preferences = new Preferences();
export const t = (key: MessageKey, values?: MessageValues) =>
  translate(key, preferences.locale, values);
