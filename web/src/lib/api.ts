import { t } from './preferences.svelte.js';
import { parse, stringify, LosslessNumber } from 'lossless-json';
import { apiFields, apiFieldName } from './api-fields.js';
import { version as appVersion } from '../../package.json';
import type { ChangeEventDetail } from '#lib/generated/api/models/ChangeEventDetail.js';
import type { ChangeEventPage } from '#lib/generated/api/models/ChangeEventPage.js';
import type { PageResponse } from '#lib/generated/api/models/PageResponse.js';
import type { StatisticsResponse } from '#lib/generated/api/models/StatisticsResponse.js';
import type { DevicesResponse } from '#lib/generated/api/models/DevicesResponse.js';
import type { SyncCapabilities } from '#lib/generated/api/models/SyncCapabilities.js';
import type { IdentityResponse } from '#lib/generated/api/models/IdentityResponse.js';
import type { LoginResponse } from '#lib/generated/api/models/LoginResponse.js';
import type { Row } from '#lib/generated/api/models/Row.js';
import type { PushResult } from '#lib/generated/api/models/PushResult.js';

export class APIError extends Error {
  constructor(
    public status: number,
    public resetRequired = false,
    public requestId = '',
  ) {
    super();
  }
  get message() {
    return this.status === 401
      ? t('your_session_expired_sign_in_again')
      : this.resetRequired
        ? t('cloud_data_was_reset_reload_to_continue')
        : this.status === 403
          ? t('this_session_cannot_perform_this_action')
          : this.status === 404
            ? t('this_record_was_not_found_or_was_deleted')
            : t('request_failed', { status: this.status });
  }
}

function operatingSystem(): string {
  if (typeof navigator === 'undefined') return 'unknown';
  const agent = navigator.userAgent;
  if (/iPhone|iPad|iPod/.test(agent) || (/Macintosh/.test(agent) && navigator.maxTouchPoints > 1))
    return 'ios';
  if (/Android/.test(agent)) return 'android';
  if (/Windows/.test(agent)) return 'windows';
  if (/Macintosh|Mac OS/.test(agent)) return 'macos';
  if (/Linux/.test(agent)) return 'linux';
  return 'unknown';
}

export function parseCloudJSON(text: string): unknown {
  return apiFields(parseExactJSON(text));
}

export function parseExactJSON(text: string): unknown {
  return parse(text, undefined, (value) => {
    const number = Number(value);
    return Number.isSafeInteger(number) && /^-?\d+$/.test(value)
      ? number
      : new LosslessNumber(value);
  });
}

async function request<T>(
  path: string,
  query: Record<string, string | number | undefined> = {},
  options: RequestInit = {},
  revisionResult = false,
): Promise<T> {
  const url = new URL(path, window.location.origin);
  for (const [key, value] of Object.entries(query))
    if (value !== undefined && value !== '') url.searchParams.set(apiFieldName(key), String(value));
  const response = await fetch(url, {
    ...options,
    credentials: 'same-origin',
    cache: 'no-store',
    redirect: 'error',
    signal: options.signal
      ? AbortSignal.any([options.signal, AbortSignal.timeout(20_000)])
      : AbortSignal.timeout(20_000),
    headers: {
      'Content-Type': 'application/json',
      'X-Recoow-Client': 'web',
      'X-Recoow-OS': operatingSystem(),
      'X-Recoow-App-Version': appVersion,
      ...options.headers,
    },
  });
  options.signal?.throwIfAborted();
  if (!response.ok) {
    const error = parseCloudJSON(await response.text().catch(() => '{}')) as {
      error?: string;
      status?: string;
    };
    if (response.status === 401 && path !== '/api/sessions')
      window.dispatchEvent(new Event('recoow:unauthorized'));
    const reset = response.status === 409 && error.error === 'reset_required';
    if (reset) window.dispatchEvent(new Event('recoow:reset'));
    if (revisionResult && !reset && [409, 422].includes(response.status) && error.status)
      return error as T;
    throw new APIError(response.status, reset, response.headers.get('X-Request-Id') ?? '');
  }
  return parseCloudJSON(await response.text()) as T;
}

export const api = {
  write: (
    table: string,
    id: string,
    method: 'POST' | 'PUT' | 'DELETE',
    body: string,
    signal?: AbortSignal,
  ) =>
    request<PushResult>(
      `/api/${table.replaceAll('_', '-')}${method === 'POST' ? '' : '/' + encodeURIComponent(id)}`,
      {},
      { method, body, signal },
      true,
    ),
  me: () => request<IdentityResponse>('/api/users/me'),
  capabilities: () => request<SyncCapabilities>('/api/sync/capabilities'),
  login: (username: string, password: string) =>
    request<LoginResponse>(
      '/api/sessions',
      {},
      { method: 'POST', body: JSON.stringify({ username, password }) },
    ),
  logout: () => request<{ ok: boolean }>('/api/sessions/current', {}, { method: 'DELETE' }),
  profile: () =>
    request<{ ok: boolean }>(
      '/api/devices/current/profile',
      {},
      {
        method: 'PUT',
        body: JSON.stringify({ client: 'web', os: operatingSystem(), app_version: appVersion }),
      },
    ),
  records: (
    table: string,
    query: Record<string, string | number | undefined>,
    signal?: AbortSignal,
  ) => request<PageResponse>(`/api/${table.replaceAll('_', '-')}`, query, { signal }),
  record: (table: string, id: string, signal?: AbortSignal) =>
    request<Row>(`/api/${table.replaceAll('_', '-')}/${encodeURIComponent(id)}`, {}, { signal }),
  statistics: (query: Record<string, string | number | undefined>, signal?: AbortSignal) =>
    request<StatisticsResponse>('/api/statistics', query, { signal }),
  history: (query: Record<string, string | number | undefined>, signal?: AbortSignal) =>
    request<ChangeEventPage>('/api/change-events', query, { signal }),
  event: (id: string, signal?: AbortSignal) =>
    request<ChangeEventDetail>(`/api/change-events/${encodeURIComponent(id)}`, {}, { signal }),
  devices: (signal?: AbortSignal) => request<DevicesResponse>('/api/devices', {}, { signal }),
};

// 快照作为不透明业务内容保留；只转换接口层字段并精确序列化数字。
export const serializeCloudJSON = (value: unknown): string =>
  stringify(apiFields(value, false)) ?? '{}';
