<script lang="ts">
  import { appAsset, appPath } from '#lib/paths.js';
  import '../app.css';
  import { onMount, type Snippet } from 'svelte';
  import { afterNavigate } from '$app/navigation';
  import { page } from '$app/state';
  import { QueryClientProvider } from '@tanstack/svelte-query';
  import { Dialog, DropdownMenu } from 'bits-ui';
  import { ModeWatcher } from 'mode-watcher';
  import {
    LayoutDashboard,
    ChartColumn,
    Files,
    History,
    MonitorSmartphone,
    RefreshCw,
    LogOut,
    PanelLeftClose,
    PanelLeftOpen,
    Menu,
    X,
    ChevronDown,
    ChevronRight,
  } from '@lucide/svelte';
  import { Button } from '#lib/components/ui/button/index.js';
  import AppIcon from '#lib/components/AppIcon.svelte';
  import Login from '#lib/components/Login.svelte';
  import Preferences from '#lib/components/Preferences.svelte';
  import { session, queryClient } from '#lib/session.svelte.js';
  import { preferences, t } from '#lib/preferences.svelte.js';
  import { recordGroups, recordGroup, groupTitle } from '#lib/record-groups.js';
  import { APIError } from '#lib/api.js';
  let { children }: { children: Snippet } = $props();
  let logoutError = $state<Error | null>(null);
  let loggingOut = $state(false);
  let collapsed = $state(false);
  let mobileOpen = $state(false);
  let main: HTMLElement | undefined = $state();
  afterNavigate(() => main?.scrollTo({ top: 0 }));
  const nav = $derived([
    { href: '/', text: t('overview'), icon: LayoutDashboard },
    { href: '/statistics', text: t('bill_statistics'), icon: ChartColumn },
    { href: '/records', text: t('records'), icon: Files },
    { href: '/history', text: t('change_history'), icon: History },
    { href: '/devices', text: t('devices'), icon: MonitorSmartphone },
  ]);
  const active = $derived(
    nav.find((item) =>
      item.href === '/'
        ? page.url.pathname === appPath('/')
        : page.url.pathname.startsWith(appPath('/' + item.href.split('/')[1])),
    ) ?? nav[0],
  );
  const selectedGroup = $derived(recordGroup(page.params.table ?? ''));
  const detail = $derived(
    selectedGroup ? groupTitle(selectedGroup) : page.params.eventId ? t('change_details') : '',
  );
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
  ><title>recoow · {t('cloud_records')}</title><meta name="robots" content="noindex, nofollow" />
  <link
    rel="icon"
    type="image/png"
    href={appAsset(preferences.dark ? 'favicon-dark.png' : 'favicon.png')}
  />
  <link rel="apple-touch-icon" href={appAsset('app-icon.png')} />
</svelte:head>
<ModeWatcher
  modeStorageKey="recoow:theme"
  themeStorageKey="recoow:palette"
  themeColors={{ light: '#f5f7fa', dark: '#141414' }}
/>

{#snippet sidebarContent()}
  <a class="brand" href={appPath('/')} onclick={() => (mobileOpen = false)}
    ><AppIcon /><span class="brand-name">recoow</span></a
  >
  <nav class="nav-list" aria-label={t('main_navigation')}>
    {#each nav as item (item.href)}
      <a
        href={appPath(item.href)}
        class:active={active.href === item.href}
        aria-current={active.href === item.href ? 'page' : undefined}
        title={collapsed ? item.text : undefined}
        onclick={() => (mobileOpen = false)}
      >
        <item.icon size={18} /><span>{item.text}</span>
      </a>
      {#if item.href === '/records' && active.href === '/records'}<div class="subnav">
          {#each recordGroups as group (group.id)}<a
              href={appPath('/records/' + group.tables[0])}
              class:active={group.id === selectedGroup?.id}
              onclick={() => (mobileOpen = false)}>{groupTitle(group)}</a
            >{/each}
        </div>{/if}
    {/each}
  </nav>
{/snippet}

<QueryClientProvider client={queryClient}>
  {#if !session.ready}<main class="session-loading" role="status">
      <AppIcon />
      <p>{t('verifying_session')}</p>
    </main>
  {:else if !session.user}<Login />
  {:else}
    <a class="skip-link" href="#main">{t('skip_to_content')}</a>
    <div class="app-shell" class:sidebar-collapsed={collapsed}>
      <aside class="sidebar">{@render sidebarContent()}</aside>
      <div class="workspace">
        <header class="topbar">
          <div class="topbar-start">
            <Button
              variant="ghost"
              size="icon"
              class="desktop-navigation-toggle"
              aria-label={collapsed ? t('expand_navigation') : t('collapse_navigation')}
              title={collapsed ? t('expand_navigation') : t('collapse_navigation')}
              onclick={() => (collapsed = !collapsed)}
            >
              {#if collapsed}<PanelLeftOpen size={18} />{:else}<PanelLeftClose size={18} />{/if}
            </Button>
            <Dialog.Root bind:open={mobileOpen}>
              <Dialog.Trigger class="mobile-navigation-toggle" aria-label={t('open_navigation')}
                ><Menu size={20} /></Dialog.Trigger
              >
              <Dialog.Portal>
                <Dialog.Overlay class="drawer-overlay" />
                <Dialog.Content class="navigation-drawer">
                  <Dialog.Title class="sr-only">{t('main_navigation')}</Dialog.Title>
                  <Dialog.Close class="drawer-close" aria-label={t('close')}
                    ><X size={18} /></Dialog.Close
                  >
                  {@render sidebarContent()}
                </Dialog.Content>
              </Dialog.Portal>
            </Dialog.Root>
            <nav class="breadcrumbs" aria-label={t('workbench')}>
              <a class="breadcrumb-home" href={appPath('/')}>{t('workbench')}</a>
              <ChevronRight class="breadcrumb-home" size={14} aria-hidden="true" />
              {#if detail}
                <a href={appPath(active.href)}>{active.text}</a>
                <ChevronRight size={14} aria-hidden="true" />
                {#if page.params.table && (page.params.id || page.url.pathname.endsWith('/new'))}
                  <a class="breadcrumb-detail" href={appPath('/records/' + page.params.table)}
                    >{detail}</a
                  >
                  <ChevronRight size={14} aria-hidden="true" />
                  {#if page.params.id && (page.url.pathname.endsWith('/edit') || page.url.pathname.endsWith('/relations'))}
                    <a href={appPath('/records/' + page.params.table + '/' + page.params.id)}
                      >{t('record_details')}</a
                    >
                    <ChevronRight size={14} aria-hidden="true" />
                  {/if}
                  <span aria-current="page"
                    >{page.url.pathname.endsWith('/new')
                      ? t('new_record')
                      : page.url.pathname.endsWith('/relations')
                        ? t('record_relations')
                        : page.url.pathname.endsWith('/edit')
                          ? t('edit_record')
                          : t('record_details')}</span
                  >
                {:else}
                  <span class="breadcrumb-detail" aria-current="page">{detail}</span>
                {/if}
              {:else}<span aria-current="page">{active.text}</span>{/if}
            </nav>
          </div>
          <div class="topbar-actions">
            <Button
              variant="ghost"
              size="icon"
              aria-label={t('refresh_cloud_data')}
              title={t('refresh_cloud_data')}
              disabled={session.checking || !session.online}
              onclick={() => session.revalidate()}
              ><RefreshCw size={17} class={session.checking ? 'spin' : ''} /></Button
            >
            <Preferences />
            <span class="topbar-divider" aria-hidden="true"></span>
            <DropdownMenu.Root>
              <DropdownMenu.Trigger class="account-trigger" aria-label={t('account_menu')}>
                <span class="avatar">{session.user.username.slice(0, 1).toUpperCase()}</span><span
                  class="username">{session.user.username}</span
                ><ChevronDown size={13} />
              </DropdownMenu.Trigger>
              <DropdownMenu.Portal
                ><DropdownMenu.Content
                  class="action-menu"
                  align="end"
                  sideOffset={8}
                  collisionPadding={12}
                >
                  <div class="account-menu-heading">
                    <small>{t('current_account')}</small><strong>{session.user.username}</strong>
                  </div>
                  <DropdownMenu.Separator class="menu-separator" />
                  <DropdownMenu.Item
                    class="action-menu-item destructive"
                    disabled={loggingOut}
                    onSelect={logout}><LogOut size={16} />{t('sign_out')}</DropdownMenu.Item
                  >
                </DropdownMenu.Content></DropdownMenu.Portal
              >
            </DropdownMenu.Root>
          </div>
        </header>
        <main bind:this={main} class="main" id="main" tabindex="-1">
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
