<script lang="ts">
  import '../app.css';
  import { onMount, type Snippet } from 'svelte';
  import { page } from '$app/state';
  import { QueryClientProvider } from '@tanstack/svelte-query';
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
    { href: '/', text: t('概览', 'Overview'), icon: LayoutDashboard },
    { href: '/records/diary_entries', text: t('记录', 'Records'), icon: Files },
    { href: '/history', text: t('变更历史', 'Change history'), icon: History },
    { href: '/devices', text: t('设备', 'Devices'), icon: MonitorSmartphone },
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
  ><title>recoow · {t('云端记录', 'Cloud records')}</title><meta
    name="robots"
    content="noindex, nofollow"
  /></svelte:head
>
<QueryClientProvider client={queryClient}>
  {#if !session.ready}<main class="status" role="status">
      {t('正在验证会话…', 'Verifying session…')}
    </main>
  {:else if !session.user}<Login />
  {:else}
    <a class="skip-link" href="#main">{t('跳到内容', 'Skip to content')}</a>
    <div class="app-shell">
      <aside class="sidebar">
        <a class="brand" href="/"><span class="brand-mark">r</span>recoow</a>
        <div>
          <p class="nav-label">{t('你的云端空间', 'Your cloud space')}</p>
          <nav class="nav-list" aria-label={t('主导航', 'Main navigation')}>
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
            ></span>{session.online
              ? t('在线 · 云端只读', 'Online · Read only')
              : t('离线', 'Offline')}</span
          ><small
            >{t('手机负责记录，云端呈现变化', 'Record on your phone, explore in the cloud')}</small
          >
        </div>
      </aside>
      <div class="workspace">
        <header class="topbar">
          <Badge variant="secondary">{t('云端只读', 'Cloud · Read only')}</Badge><Preferences />
          <div class="topbar-actions">
            <span class="username">{session.user.username}</span><Button
              variant="ghost"
              size="icon"
              aria-label={t('刷新云端数据', 'Refresh cloud data')}
              disabled={session.checking || !session.online}
              onclick={() => session.revalidate()}><RefreshCw size={17} /></Button
            ><Button
              variant="ghost"
              size="icon"
              aria-label={t('退出登录', 'Sign out')}
              disabled={loggingOut}
              onclick={logout}><LogOut size={17} /></Button
            >
          </div>
        </header>
        <main class="main" id="main" tabindex="-1">
          {#if !session.online}<div class="notice" role="status">
              {t(
                '当前离线，已加载的内容可能不是最新数据。联网后会重新检查。',
                'You are offline. Loaded content may be out of date; it will be checked when you reconnect.',
              )}
            </div>{/if}
          {#if session.error === 'network'}<div class="notice" role="status">
              {t(
                '暂时无法获取云端最新数据，请重试',
                'Unable to check the latest cloud data. Try again.',
              )}
            </div>{/if}
          {#if logoutError}<div class="notice error" role="alert">
              {logoutError instanceof APIError
                ? logoutError.message
                : t('退出失败，请重试', 'Unable to sign out. Try again.')}
            </div>{/if}
          {#key `${session.user.accountId}:${session.epoch}`}{@render children()}{/key}
        </main>
      </div>
    </div>
  {/if}
</QueryClientProvider>
