<script lang="ts">
  import { Button } from '#lib/components/ui/button/index.js';
  import AppIcon from './AppIcon.svelte';
  import Preferences from './Preferences.svelte';
  import { session } from '#lib/session.svelte.js';
  import { APIError } from '#lib/api.js';
  import { t } from '#lib/preferences.svelte.js';
  import { Eye, EyeOff, ArrowRight } from '@lucide/svelte';
  let username = $state('');
  let password = $state('');
  let busy = $state(false);
  let error = $state<unknown>(null);
  let showPassword = $state(false);
  async function login(event: SubmitEvent) {
    event.preventDefault();
    busy = true;
    error = null;
    try {
      await session.login(username.trim(), password);
      password = '';
    } catch (cause) {
      error = cause;
    } finally {
      busy = false;
    }
  }
</script>

<main class="login-shell">
  <header class="login-header">
    <a class="brand" href="/"><AppIcon />recoow</a><Preferences />
  </header>
  <section class="login-card">
    <h1>{t('your_records_in_the_cloud')}</h1>
    {#if session.error === 'expired'}<div class="notice" role="status">
        {t('your_session_expired_sign_in_again')}
      </div>{/if}
    {#if session.error === 'network'}<div class="notice" role="status">
        {t('unable_to_reach_the_cloud_check_your_connection_and_try_again')}
        <Button variant="link" disabled={session.checking} onclick={() => session.revalidate()}
          >{t('retry')}</Button
        >
      </div>{/if}
    {#if session.error === 'reset'}<div class="notice" role="status">
        {t('cloud_data_was_reset_verify_your_session_again')}
      </div>
      <Button variant="outline" onclick={() => session.revalidate()}>{t('reload')}</Button>{/if}
    <form class="login-form" onsubmit={login}>
      <label class="form-field"
        >{t('username')}<input
          autocomplete="username"
          required
          maxlength="120"
          bind:value={username}
          disabled={busy}
        /></label
      >
      <div class="form-field">
        <label for="login-password">{t('password')}</label>
        <div class="password-input">
          <input
            id="login-password"
            type={showPassword ? 'text' : 'password'}
            autocomplete="current-password"
            required
            bind:value={password}
            disabled={busy}
          /><button
            type="button"
            class="password-toggle"
            aria-label={showPassword ? t('hide_password') : t('show_password')}
            onclick={() => (showPassword = !showPassword)}
            >{#if showPassword}<EyeOff size={17} />{:else}<Eye size={17} />{/if}</button
          >
        </div>
      </div>
      {#if error}<div class="notice error" role="alert">
          {error instanceof APIError && error.status === 401
            ? t('incorrect_username_or_password')
            : error instanceof APIError
              ? error.message
              : t('unable_to_sign_in_check_your_connection_and_try_again')}
        </div>{/if}
      <Button
        type="submit"
        class="login-submit"
        disabled={busy || !session.online || session.checking}
        >{busy ? t('signing_in') : t('sign_in')}<ArrowRight size={16} /></Button
      >
    </form>
  </section>
</main>
