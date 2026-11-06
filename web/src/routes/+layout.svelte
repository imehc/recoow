<script lang="ts">
  import '../app.css';
  import { onMount, type Snippet } from 'svelte';
  import { page } from '$app/state';
  import { QueryClientProvider } from '@tanstack/svelte-query';
  import { ModeWatcher } from 'mode-watcher';
  import {
    LayoutDashboard,
    Files,
    History,
    MonitorSmartphone,
    RefreshCw,
    LogOut,
  } from '@lucide/svelte';
  import { Button } from '#lib/components/ui/button/index.js';
  import { Badge } from '#lib/components/ui/badge/index.js';
  import Login from '#lib/components/Login.svelte';
  import Preferences from '#lib/components/Preferences.svelte';
  import { session, queryClient } from '#lib/session.svelte.js';
  import { preferences, t } from '#lib/preferences.svelte.js';
  import { APIError } from '#lib/api.js';
  let { children }: { children: Snippet } = $props();
  let logoutError = $state<Error | null>(null);
  let loggingOut = $state(false);
  const nav = $derived([
    { href: '/', text: t('overview'), icon: LayoutDashboard },
    { href: '/records/diary_entries', text: t('records'), icon: Files },
    { href: '/history', text: t('change_history'), icon: History },
    { href: '/devices', text: t('devices'), icon: MonitorSmartphone },
  ]);
  onMount(() => {
    const stopPreferences = preferences.start();
    const stopSession = session.start();
    return () => {
      stopSession();
      stopPreferences();
    };
  });
  async function logout() {
    loggingOut = true;
    logoutError = null;
    try {
      await session.logout();
    } catch (error) {
      logoutError = error as Error;
    } finally {
      loggingOut = false;
    }
  }
</script>

<svelte:head
  ><title>recoow · {t('cloud_records')}</title><meta
    name="robots"
    content="noindex, nofollow"
  /></svelte:head
>
<ModeWatcher
  modeStorageKey="recoow:theme"
  themeStorageKey="recoow:palette"
  themeColors={{ light: '#f6f7fb', dark: '#131824' }}
/>
<QueryClientProvider client={queryClient}>
  {#if !session.ready}<main class="status" role="status">
      {t('verifying_session')}
    </main>
  {:else if !session.user}<Login />
  {:else}
    <a class="skip-link" href="#main">{t('skip_to_content')}</a>
    <div class="app-shell">
      <aside class="sidebar">
        <a class="brand" href="/"><span class="brand-mark">r</span>recoow</a>
        <div>
          <p class="nav-label">{t('your_cloud_space')}</p>
          <nav class="nav-list" aria-label={t('main_navigation')}>
            {#each nav as item (item.href)}<a
                href={item.href}
                class:active={item.href === '/'
                  ? page.url.pathname === '/'
                  : page.url.pathname.startsWith(
                      item.href.split('/')[1] === 'records' ? '/records' : item.href,
                    )}
                aria-current={(
                  item.href === '/'
                    ? page.url.pathname === '/'
                    : page.url.pathname.startsWith('/' + item.href.split('/')[1])
                )
                  ? 'page'
                  : undefined}><item.icon size={18} />{item.text}</a
              >{/each}
          </nav>
        </div>
        <div class="sidebar-footer">
          <span
            ><span class="online-dot" style:background={session.online ? undefined : '#c99446'}
            ></span>{session.online ? t('online_read_only') : t('offline')}</span
          ><small>{t('record_on_your_phone_explore_in_the_cloud')}</small>
        </div>
      </aside>
      <div class="workspace">
        <header class="topbar">
          <Badge variant="secondary">{t('cloud_read_only')}</Badge>
          <div class="topbar-actions">
            <Preferences /><span class="topbar-divider" aria-hidden="true"></span>
            <span class="username">{session.user.username}</span><Button
              variant="ghost"
              size="icon"
              aria-label={t('refresh_cloud_data')}
              disabled={session.checking || !session.online}
              onclick={() => session.revalidate()}><RefreshCw size={17} /></Button
            ><Button
              variant="ghost"
              size="icon"
              aria-label={t('sign_out')}
              disabled={loggingOut}
              onclick={logout}><LogOut size={17} /></Button
            >
          </div>
        </header>
        <main class="main" id="main" tabindex="-1">
          {#if !session.online}<div class="notice" role="status">
              {t('you_are_offline_loaded_content_may_be_out_of_date_it_will_be_checked_whe')}
            </div>{/if}
          {#if session.error === 'network'}<div class="notice" role="status">
              {t('unable_to_check_the_latest_cloud_data_try_again')}
            </div>{/if}
          {#if logoutError}<div class="notice error" role="alert">
              {logoutError instanceof APIError
                ? logoutError.message
                : t('unable_to_sign_out_try_again')}
            </div>{/if}
          {#key `${session.user.accountId}:${session.epoch}`}{@render children()}{/key}
        </main>
      </div>
    </div>
  {/if}
</QueryClientProvider>
