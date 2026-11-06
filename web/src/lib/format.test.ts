import { describe, expect, it } from 'vitest';
import { parseCloudJSON } from './api';
import { dateRange, integer, money, valueText, source } from './format';
import { preferences, resolveLanguage } from './preferences.svelte';
import { mediaReferences, mediaURL } from './media';

describe('cloud values', () => {
  it('preserves integer cents above JavaScript’s safe range', () => {
    const data = parseCloudJSON(
      '{"amount":9007199254740993,"negative":-1,"nested":{"large":9223372036854775807}}',
    ) as Record<string, unknown>;
    expect(integer(data.amount)).toBe(9007199254740993n);
    expect(money(data.amount)).toBe('¥90,071,992,547,409.93');
    expect(money(data.negative)).toBe('-¥0.01');
    expect(valueText('bills', 'future', data.nested)).toContain('9223372036854775807');
    expect(integer(9007199254740992)).toBeNull();
  });
  it('distinguishes null from an absent field', () => {
    preferences.language = 'en';
    expect(valueText('bills', 'note', null)).toBe('Null');
    expect(valueText('bills', 'note', null, false)).toBe('Missing');
    expect(valueText('bills', 'note', '')).toBe('');
  });
  it('uses inclusive local dates and an exclusive end boundary', () => {
    const range = dateRange('2026-11-06', '2026-11-06');
    expect(range.fromMs).toBe(new Date(2026, 10, 6).getTime());
    expect(range.toMs).toBe(new Date(2026, 10, 7).getTime());
    expect(dateRange('', '')).toEqual({ fromMs: undefined, toMs: undefined });
  });
  it('keeps browser platform separate from the operating system', () => {
    expect(source({ client: 'web', os: 'ios' })).toBe('Web · iOS');
  });
});

describe('preferences and attachments', () => {
  it('uses browser language only in system mode', () => {
    expect(resolveLanguage('system', ['zh-Hans-CN'])).toBe('zh');
    expect(resolveLanguage('system', ['fr-FR'])).toBe('en');
    expect(resolveLanguage('zh', ['en-US'])).toBe('zh');
  });
  it('accepts cloud references and keeps keys on the authenticated origin', () => {
    expect(
      mediaReferences({
        _media: { object: { key: 'media/a b.jpg', contentType: 'image/jpeg' }, missing: null },
      }),
    ).toEqual([{ key: 'media/a b.jpg', contentType: 'image/jpeg' }]);
    expect(mediaURL('https://example.com/a?b')).toBe(
      '/api/media/objects/https%3A//example.com/a%3Fb',
    );
    expect(mediaReferences({ _media: 'invalid' })).toEqual([]);
  });
});

describe('client enum labels', () => {
  it('matches mobile values in details and historical snapshots without losing unknown values', () => {
    preferences.language = 'zh';
    expect(valueText('bills', 'payment_method', 'wechat')).toBe('微信');
    expect(valueText('bills', 'transaction_type', 'storedValueUse')).toBe('储值消费');
    expect(valueText('bills', 'category', 'other', true, { transaction_type: 'income' })).toBe(
      '其他收入',
    );
    expect(valueText('bills', 'category', 'other', true, { transaction_type: 'expense' })).toBe(
      '其他',
    );
    expect(valueText('food_entries', 'meal_kind', 'lateNightSnack')).toBe('夜宵');
    expect(valueText('reminder_completions', 'kind', 'makeUp')).toBe('补签');
    expect(valueText('tracks', 'status', 'finished')).toBe('已完成');
    expect(valueText('bills', 'payment_method', 'legacy-wallet')).toBe('legacy-wallet');
    expect(valueText('diary_entries', 'content', 'wechat')).toBe('wechat');
    preferences.language = 'en';
    expect(valueText('bills', 'payment_method', 'wechat')).toBe('WeChat');
    expect(valueText('anniversaries', 'date_calendar', 'chinese')).toBe('Lunar');
  });
  it('formats reminder clock time while preserving out of range legacy data', () => {
    expect(valueText('reminders', 'reminder_time_minutes', 1200)).toBe('20:00');
    expect(valueText('reminders', 'reminder_time_minutes', 0)).toBe('00:00');
    expect(valueText('reminders', 'reminder_time_minutes', 1500)).toBe('1500');
  });
});
