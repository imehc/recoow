import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, APIError } from './api';

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
      .mockResolvedValue(new Response('{"userId":"u","username":"name","accountId":"a"}'));
    vi.stubGlobal('fetch', fetchMock);
    await api.me();
    const [url, options] = fetchMock.mock.calls[0];
    expect(url.origin).toBe('https://recoow.example');
    expect(options).toMatchObject({
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      headers: { 'X-Recoow-Client': 'web', 'X-Recoow-OS': 'ios' },
    });
    expect(options.headers.Authorization).toBeUndefined();
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
});
