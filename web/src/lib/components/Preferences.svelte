<script lang="ts">
  import { preferences, t, type Language, type Theme } from '#lib/preferences.svelte.js';
</script>

<div class="preferences">
  <label
    >{t('主题', 'Theme')}
    <select
      aria-label={t('主题', 'Theme')}
      value={preferences.theme}
      onchange={(event) => preferences.setTheme(event.currentTarget.value as Theme)}
    >
      <option value="system">{t('跟随系统', 'System')}</option>
      <option value="light">{t('浅色', 'Light')}</option>
      <option value="dark">{t('深色', 'Dark')}</option>
    </select>
  </label>
  <label
    >{t('语言', 'Language')}
    <select
      aria-label={t('语言', 'Language')}
      value={preferences.language}
      onchange={(event) => preferences.setLanguage(event.currentTarget.value as Language)}
    >
      <option value="system">{t('跟随浏览器', 'Browser')}</option>
      <option value="zh">简体中文</option>
      <option value="en">English</option>
    </select>
  </label>
</div>
