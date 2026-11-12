import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, APIError } from './api';
import { version } from '../../package.json';

describe('authenticated transport', () => {
  let events: EventTarget;
  beforeEach(() => {
    events = new EventTarget();
    vi.stubGlobal('window', {
      location: { origin: 'https://recoow.example' },
      dispatchEvent: events.dispatchEvent.bind(events),
    });
    vi.stubGlobal('navigator', { userAgent: 'iPhone', maxTouchPoints: 1 });
  });
  afterEach(() => vi.unstubAllGlobals());

  it('uses same-origin cookies without caching and identifies Web on iOS', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response('{"user_id":"u","username":"name","account_id":"a"}'));
    vi.stubGlobal('fetch', fetchMock);
    expect(await api.me()).toMatchObject({ userId: 'u', accountId: 'a' });
    const [url, options] = fetchMock.mock.calls[0];
    expect(url.origin).toBe('https://recoow.example');
    expect(options).toMatchObject({
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      headers: {
        'X-Recoow-Client': 'web',
        'X-Recoow-OS': 'ios',
        'X-Recoow-App-Version': version,
      },
    });
    expect(options.headers.Authorization).toBeUndefined();
    expect(options.headers['X-Recoow-Field-Case']).toBeUndefined();
  });
  it('sends canonical query and profile fields', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
    vi.stubGlobal('fetch', fetchMock);
    await api.history({ deviceId: 'device', fromMs: 1000, toMs: 2000 });
    expect(fetchMock.mock.calls[0][0].search).toBe('?device_id=device&from_ms=1000&to_ms=2000');
    await api.profile();
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toMatchObject({ app_version: version });
  });
  it('treats rejected login differently from session expiry', async () => {
    const listener = vi.fn();
    events.addEventListener('recoow:unauthorized', listener);
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(() => Promise.resolve(new Response('{}', { status: 401 }))),
    );
    await expect(api.login('user', 'wrong')).rejects.toBeInstanceOf(APIError);
    expect(listener).not.toHaveBeenCalled();
    await expect(api.me()).rejects.toMatchObject({ status: 401 });
    expect(listener).toHaveBeenCalledTimes(1);
  });
  it('clears stale history only for dataset reset responses', async () => {
    const listener = vi.fn();
    events.addEventListener('recoow:reset', listener);
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response('{"error":"reset_required"}', {
          status: 409,
          headers: { 'X-Request-Id': 'request-test' },
        }),
      ),
    );
    await expect(api.history({})).rejects.toMatchObject({
      resetRequired: true,
      requestId: 'request-test',
    });
    expect(listener).toHaveBeenCalledTimes(1);
  });
  it('does not process an obsolete response after cancellation', async () => {
    const controller = new AbortController();
    const listener = vi.fn();
    events.addEventListener('recoow:unauthorized', listener);
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(async () => {
        controller.abort();
        return new Response('{}', { status: 401 });
      }),
    );
    await expect(api.records('bills', {}, controller.signal)).rejects.toMatchObject({
      name: 'AbortError',
    });
    expect(listener).not.toHaveBeenCalled();
  });
  it('formats the HTTP status in translated errors', () => {
    expect(new APIError(502).message).toBe('请求失败（502）');
  });
  it('returns conflict and dependency receipts while dataset resets still clear the session', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            '{"status":"conflict","table":"diary_tags","id":"tag","version":9007199254740993,"current":{"snapshot":{"future":9007199254740993}}}',
            { status: 409 },
          ),
        ),
    );
    const receipt = await api.write('diary_tags', 'tag', 'PUT', '{}');
    expect(String(receipt.version)).toBe('9007199254740993');
    expect(receipt.status).toBe('conflict');
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('{"error":"reset_required"}', { status: 409 })),
    );
    await expect(api.write('diary_tags', 'tag', 'DELETE', '{}')).rejects.toMatchObject({
      resetRequired: true,
    });
  });
});
