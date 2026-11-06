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
  <section class="panel login-card">
    <div class="brand"><span class="brand-mark">r</span>recoow</div>
    <h1>{t('查看你的云端记录', 'Your records, in the cloud')}</h1>
    <p>
      {t(
        '查看已同步的记录、统计和变化。请使用手机端已绑定的用户名与密码登录。',
        'Browse synced records, statistics and changes. Sign in with the username and password linked in your mobile app.',
      )}
    </p>
    {#if session.error === 'expired'}<div class="notice" role="status">
        {t('登录已过期，请重新登录', 'Your session expired. Sign in again.')}
      </div>{/if}
    {#if session.error === 'network'}<div class="notice" role="status">
        {t(
          '暂时无法连接云端，请检查网络后重试',
          'Unable to reach the cloud. Check your connection and try again.',
        )}
      </div>{/if}
    {#if session.error === 'reset'}<div class="notice" role="status">
        {t('云端数据已重建，请重新验证会话', 'Cloud data was reset. Verify your session again.')}
      </div>
      <Button variant="outline" onclick={() => session.revalidate()}
        >{t('重新加载', 'Reload')}</Button
      >{/if}
    <form class="login-form" onsubmit={login}>
      <label
        >{t('用户名', 'Username')}<input
          autocomplete="username"
          required
          maxlength="120"
          bind:value={username}
          disabled={busy}
        /></label
      >
      <label
        >{t('密码', 'Password')}<input
          type="password"
          autocomplete="current-password"
          required
          bind:value={password}
          disabled={busy}
        /></label
      >
      {#if error}<div class="notice error" role="alert">
          {error instanceof APIError && error.status === 401
            ? t('用户名或密码错误', 'Incorrect username or password.')
            : error instanceof APIError
              ? error.message
              : t(
                  '登录失败，请检查网络后重试',
                  'Unable to sign in. Check your connection and try again.',
                )}
        </div>{/if}
      <Button type="submit" disabled={busy || !session.online || session.checking}
        >{busy ? t('正在登录…', 'Signing in…') : t('登录', 'Sign in')}</Button
      >
    </form>
    <p class="login-footnote">
      {t(
        '仅展示云端已收到的数据；手机上尚未同步的修改不会出现在这里。',
        'Only data received by the cloud is shown. Unsynced changes on your phone are not included.',
      )}
    </p>
    <Preferences />
  </section>
</main>
