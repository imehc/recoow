<script lang="ts">
  import { appPath } from '#lib/paths.js';
  import { Button } from '#lib/components/ui/button/index.js';
  import AppIcon from './AppIcon.svelte';
  import Preferences from './Preferences.svelte';
  import { session } from '#lib/session.svelte.js';
  import { APIError } from '#lib/api.js';
  import { t } from '#lib/preferences.svelte.js';
  import { Tabs } from 'bits-ui';
  import { Eye, EyeOff } from '@lucide/svelte';
  let method = $state('password');
  let code = $state('');
  let username = $state('');
  let password = $state('');
  let busy = $state(false);
  let error = $state<unknown>(null);
  let showPassword = $state(false);
  async function login(event: SubmitEvent) {
    event.preventDefault();
    if (busy) return;
    busy = true;
    error = null;
    try {
      if (method === 'pairing') await session.loginPairing(code.trim());
      else await session.login(username.trim(), password);
      code = '';
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
    <a class="brand" href={appPath('/')}>
      <AppIcon />
      recoow
    </a>
    <Preferences />
  </header>
  <section class="login-card">
    <h1>{t('your_records_in_the_cloud')}</h1>
    {#if session.error === 'expired'}
      <div class="notice" role="status">
        {t('your_session_expired_sign_in_again')}
      </div>
    {/if}
    {#if session.error === 'network'}
      <div class="notice" role="status">
        {t('unable_to_reach_the_cloud_check_your_connection_and_try_again')}
        <Button variant="link" disabled={session.checking} onclick={() => session.revalidate()}>
          {t('retry')}
        </Button>
      </div>
    {/if}
    {#if session.error === 'reset'}
      <div class="notice" role="status">
        {t('cloud_data_was_reset_verify_your_session_again')}
      </div>
      <Button variant="outline" onclick={() => session.revalidate()}>{t('reload')}</Button>
    {/if}
    <Tabs.Root
      bind:value={method}
      onValueChange={() => {
        error = null;
        password = '';
        code = '';
      }}
    >
      <Tabs.List class="record-tabs login-methods" aria-label={t('login_method')}>
        <Tabs.Trigger value="password" disabled={busy}>{t('password_login')}</Tabs.Trigger>
        <Tabs.Trigger value="pairing" disabled={busy}>{t('pairing_login')}</Tabs.Trigger>
      </Tabs.List>
      <Tabs.Content value={method}>
        <form class="login-form" onsubmit={login}>
          {#if method === 'password'}
            <label class="form-field">
              {t('username')}
              <input
                autocomplete="username"
                required
                maxlength="120"
                bind:value={username}
                disabled={busy}
              />
            </label>
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
                />
                <button
                  type="button"
                  class="password-toggle"
                  aria-label={showPassword ? t('hide_password') : t('show_password')}
                  onclick={() => (showPassword = !showPassword)}
                >
                  {#if showPassword}
                    <EyeOff size={17} />
                  {:else}
                    <Eye size={17} />
                  {/if}
                </button>
              </div>
            </div>
          {:else}
            <label class="form-field">
              {t('pairing_code')}
              <input
                autocomplete="one-time-code"
                inputmode="numeric"
                pattern={'[0-9]{10}'}
                minlength="10"
                maxlength="10"
                required
                bind:value={code}
                disabled={busy}
              />
            </label>
            <p class="filter-hint">{t('pairing_login_hint')}</p>
          {/if}
          {#if error}
            <div class="notice error" role="alert">
              {error instanceof APIError && error.status === 401
                ? t(
                    method === 'pairing'
                      ? 'invalid_pairing_code'
                      : 'incorrect_username_or_password',
                  )
                : error instanceof APIError
                  ? error.message
                  : t('unable_to_sign_in_check_your_connection_and_try_again')}
            </div>
          {/if}
          <Button
            type="submit"
            class="login-submit"
            disabled={busy || !session.online || session.checking}
          >
            {busy ? t('signing_in') : t('sign_in')}
          </Button>
        </form>
      </Tabs.Content>
    </Tabs.Root>
  </section>
</main>
