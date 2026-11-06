import { QueryClient } from '@tanstack/svelte-query';
import { api, APIError } from './api';
import { t } from './preferences.svelte.js';
import type { IdentityResponse } from '#lib/generated/api/models/IdentityResponse.js';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 300_000,
      retry: (count, error) => !(error instanceof APIError) && count < 1,
      refetchOnWindowFocus: false,
    },
  },
});

export class Session {
  user = $state<IdentityResponse | null>(null);
  ready = $state(false);
  online = $state(true);
  generation = $state('');
  historySupported = $state(false);
  epoch = $state(0);
  error = $state<'' | 'expired' | 'network' | 'reset'>('');
  checkedAt = $state(0);
  checking = $state(false);
  private revision = 0;
  private pending: Promise<void> | null = null;

  clear() {
    this.revision++;
    this.user = null;
    this.generation = '';
    this.historySupported = false;
    this.epoch++;
    void queryClient.cancelQueries();
    queryClient.clear();
  }

  revalidate(): Promise<void> {
    if (this.pending) return this.pending;
    this.pending = this.validate().finally(() => {
      this.pending = null;
    });
    return this.pending;
  }

  private async validate() {
    this.checking = true;
    const revision = this.revision;
    try {
      const user = await api.me();
      const caps = await api.capabilities();
      if (revision !== this.revision) return;
      if (caps.accountId !== user.accountId) {
        this.clear();
        this.error = 'network';
        return;
      }
      if (this.user?.accountId !== user.accountId || this.generation !== caps.dataset.generation) {
        await queryClient.cancelQueries();
        queryClient.clear();
        this.epoch++;
      }
      if (revision !== this.revision) return;
      this.user = user;
      this.generation = caps.dataset.generation;
      this.historySupported = caps.features?.includes('change_history_v1') ?? false;
      this.checkedAt = Date.now();
      this.error = '';
      await queryClient.invalidateQueries();
    } catch (error) {
      if (error instanceof APIError && error.status === 401) {
        const hadUser = !!this.user;
        this.clear();
        if (hadUser) this.error = 'expired';
      } else if (revision === this.revision) this.error = 'network';
    } finally {
      this.ready = true;
      this.checking = false;
    }
  }

  async login(username: string, password: string) {
    if (this.pending) await this.pending;
    this.clear();
    this.error = '';
    await api.login(username, password);
    await this.revalidate();
    if (!this.user)
      throw new Error(t('会话暂时无法验证，请重试', 'Unable to verify the session. Try again.'));
  }

  async logout() {
    await api.logout();
    this.clear();
    this.error = '';
  }

  start() {
    this.online = navigator.onLine;
    const unauthorized = () => {
      const hadUser = !!this.user;
      this.clear();
      if (hadUser) this.error = 'expired';
    };
    const reset = () => {
      this.clear();
      this.error = 'reset';
      void (this.pending ?? Promise.resolve()).then(() => this.revalidate());
    };
    const online = () => {
      this.online = true;
      void this.revalidate();
    };
    const offline = () => {
      this.online = false;
    };
    const focus = () => {
      if (document.visibilityState === 'visible') void this.revalidate();
    };
    window.addEventListener('recoow:unauthorized', unauthorized);
    window.addEventListener('recoow:reset', reset);
    window.addEventListener('online', online);
    window.addEventListener('offline', offline);
    window.addEventListener('focus', focus);
    document.addEventListener('visibilitychange', focus);
    void this.revalidate();
    return () => {
      window.removeEventListener('recoow:unauthorized', unauthorized);
      window.removeEventListener('recoow:reset', reset);
      window.removeEventListener('online', online);
      window.removeEventListener('offline', offline);
      window.removeEventListener('focus', focus);
      document.removeEventListener('visibilitychange', focus);
      this.clear();
    };
  }
}
export const session = new Session();
