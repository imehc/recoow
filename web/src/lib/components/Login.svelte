<script lang="ts">
  import { Button } from '#lib/components/ui/button/index.js';
  import Preferences from './Preferences.svelte';
  import { session } from '#lib/session.svelte.js';
  import { APIError } from '#lib/api.js';
  import { t } from '#lib/preferences.svelte.js';
  let username = $state('');
  let password = $state('');
  let busy = $state(false);
  let error = $state<unknown>(null);
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
  <div class="login-preferences"><Preferences /></div>
  <section class="panel login-card">
    <div class="brand"><span class="brand-mark">r</span>recoow</div>
    <h1>{t('your_records_in_the_cloud')}</h1>
    <p>
      {t('browse_synced_records_statistics_and_changes_sign_in_with_the_username_a')}
    </p>
    {#if session.error === 'expired'}<div class="notice" role="status">
        {t('your_session_expired_sign_in_again')}
      </div>{/if}
    {#if session.error === 'network'}<div class="notice" role="status">
        {t('unable_to_reach_the_cloud_check_your_connection_and_try_again')}
      </div>{/if}
    {#if session.error === 'reset'}<div class="notice" role="status">
        {t('cloud_data_was_reset_verify_your_session_again')}
      </div>
      <Button variant="outline" onclick={() => session.revalidate()}>{t('reload')}</Button>{/if}
    <form class="login-form" onsubmit={login}>
      <label
        >{t('username')}<input
          autocomplete="username"
          required
          maxlength="120"
          bind:value={username}
          disabled={busy}
        /></label
      >
      <label
        >{t('password')}<input
          type="password"
          autocomplete="current-password"
          required
          bind:value={password}
          disabled={busy}
        /></label
      >
      {#if error}<div class="notice error" role="alert">
          {error instanceof APIError && error.status === 401
            ? t('incorrect_username_or_password')
            : error instanceof APIError
              ? error.message
              : t('unable_to_sign_in_check_your_connection_and_try_again')}
        </div>{/if}
      <Button type="submit" disabled={busy || !session.online || session.checking}
        >{busy ? t('signing_in') : t('sign_in')}</Button
      >
    </form>
    <p class="login-footnote">
      {t('only_data_received_by_the_cloud_is_shown_unsynced_changes_on_your_phone')}
    </p>
  </section>
</main>
