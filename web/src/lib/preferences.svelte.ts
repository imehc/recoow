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
  theme = $state<Theme>('system');
  language = $state<Language>('system');
  systemDark = $state(false);
  browserLanguages = $state<readonly string[]>(['zh']);
  locale = $derived(resolveLanguage(this.language, this.browserLanguages));
  dark = $derived(this.theme === 'dark' || (this.theme === 'system' && this.systemDark));

  text(zh: string, en: string) {
    return this.locale === 'zh' ? zh : en;
  }

  private apply() {
    document.documentElement.classList.toggle('dark', this.dark);
    document.documentElement.lang = this.locale === 'zh' ? 'zh-CN' : 'en';
  }

  setTheme(theme: Theme) {
    this.theme = theme;
    this.persist();
  }
  setLanguage(language: Language) {
    this.language = language;
    this.persist();
  }

  private persist() {
    try {
      localStorage.setItem(
        'recoow:preferences',
        JSON.stringify({ theme: this.theme, language: this.language }),
      );
    } catch {
      /* Storage can be disabled. */
    }
    this.apply();
  }

  start() {
    this.browserLanguages = navigator.languages;
    try {
      const saved = JSON.parse(localStorage.getItem('recoow:preferences') ?? '{}');
      if (['system', 'light', 'dark'].includes(saved.theme)) this.theme = saved.theme;
      if (['system', 'zh', 'en'].includes(saved.language)) this.language = saved.language;
    } catch {
      /* Ignore invalid or unavailable preferences. */
    }
    const media = matchMedia('(prefers-color-scheme: dark)');
    this.systemDark = media.matches;
    const themeChanged = () => {
      this.systemDark = media.matches;
      this.apply();
    };
    const languageChanged = () => {
      this.browserLanguages = navigator.languages;
      this.apply();
    };
    media.addEventListener('change', themeChanged);
    window.addEventListener('languagechange', languageChanged);
    this.apply();
    return () => {
      media.removeEventListener('change', themeChanged);
      window.removeEventListener('languagechange', languageChanged);
    };
  }
}

export const preferences = new Preferences();
export const t = (zh: string, en: string) => preferences.text(zh, en);
