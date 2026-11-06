import { describe, expect, it } from 'vitest';
import { parseCloudJSON } from './api';
import { apiFields } from './api-fields';

describe('snake_case HTTP contract', () => {
  it('reads canonical envelopes while preserving business keys and exact numbers', () => {
    const result = parseCloudJSON(
      `{"event_id":"e","original_source":{"device_id":"d"},"before_value":{"deviceId":"business","amount":9007199254740993},"snapshot":{"updatedAtMs":null},"record_counts":{"diary_entries":1}}`,
    ) as Record<string, unknown>;
    expect(result.eventId).toBe('e');
    expect(result.originalSource).toEqual({ deviceId: 'd' });
    expect(result.snapshot).toEqual({ updatedAtMs: null });
    expect(result.recordCounts).toEqual({ diary_entries: 1 });
    const before = result.beforeValue as Record<string, unknown>;
    expect(before.deviceId).toBe('business');
    expect(String(before.amount)).toBe('9007199254740993');
  });
  it('keeps legacy responses readable and rejects ambiguous field names', () => {
    expect(parseCloudJSON('{"accountId":"a"}')).toEqual({ accountId: 'a' });
    expect(() => parseCloudJSON('{"accountId":"a","account_id":"b"}')).toThrow();
    expect(apiFields({ mutationId: 'm', snapshot: { deviceId: 'business' } }, false)).toEqual({
      mutation_id: 'm',
      snapshot: { deviceId: 'business' },
    });
  });
});
