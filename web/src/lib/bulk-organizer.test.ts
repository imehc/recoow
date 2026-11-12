import { describe, it, expect } from 'vitest';
import { LosslessNumber, stringify } from 'lossless-json';
import { organizeSnapshot, prepareBulk } from './bulk-organizer.js';
import { parseCloudJSON } from './api.js';
import type { Row } from './generated/api/models/Row.js';
import { newSnapshot } from './record-editor.js';
const row = (table: string, snapshot: Record<string, unknown>): Row => ({
  table,
  id: 'r',
  snapshot: { ...newSnapshot(table, 'r', 100), title: '示例', ...snapshot },
  version: 8,
  dataVersion: 1,
  updatedAtMs: 100,
  deviceId: 'synthetic-device',
});
describe('bulk organization', () => {
  it('removes by key and preserves unknown tag fields and integer precision', () => {
    const original = row('diary_entries', {
      tags_json:
        '[{"key":"a","value":"相同名称","future":true},{"key":"b","value":"相同名称","future":42}]',
      content: '正文',
      future: new LosslessNumber('9007199254740993'),
    });
    const { snapshot } = organizeSnapshot('diary_entries', original, {
      kind: 'remove_tag',
      key: 'a',
      value: '相同名称',
    });
    expect(snapshot.tags_json).toBe('[{"key":"b","value":"相同名称","future":42}]');
    expect(stringify(snapshot.future)).toBe('9007199254740993');
    expect(original.snapshot?.tags_json).toContain('"key":"a"');
  });
  it('adds once by key while preserving explicit null on no-op removal', () => {
    const original = row('diary_entries', { tags_json: null, content: '正文' });
    expect(
      organizeSnapshot('diary_entries', original, {
        kind: 'remove_tag',
        key: 'missing',
        value: '标签',
      }).snapshot.tags_json,
    ).toBeNull();
    const added = organizeSnapshot('diary_entries', original, {
      kind: 'add_tag',
      key: 'new',
      value: '标签',
    }).snapshot;
    expect(added.tags_json).toBe('[{"key":"new","value":"标签"}]');
    expect(
      organizeSnapshot(
        'diary_entries',
        { ...original, snapshot: added },
        { kind: 'add_tag', key: 'new', value: '改名' },
      ).snapshot.tags_json,
    ).toBe(added.tags_json);
  });
  it('does not overwrite invalid tags and freezes each baseline for retries', () => {
    const valid = row('diary_entries', { tags_json: '[]', content: '正文' });
    const invalid = {
      ...row('diary_entries', { tags_json: '["legacy"]', content: '正文' }),
      id: 'bad',
    };
    const items = prepareBulk(
      'diary_entries',
      [valid, invalid],
      { kind: 'add_tag', key: 'k', value: '标签' },
      'g',
    );
    expect(items.map((item) => item.state)).toEqual(['ready', 'invalid']);
    valid.version = 99;
    valid.snapshot!.content = '刷新内容';
    const body = parseCloudJSON(items[0].write!.body) as Record<string, unknown>;
    expect(body.baseVersion).toBe(8);
    expect(body.generation).toBe('g');
    expect((body.snapshot as Record<string, unknown>).content).toBe('正文');
    expect(Object.isFrozen(items[0].write)).toBe(true);
  });
  it('updates only location or category and limits the selection', () => {
    const original = row('stored_items', {
      location: '柜子',
      category_id: null,
      note: '备注',
      future: { x: true },
    });
    const changed = organizeSnapshot('stored_items', original, {
      kind: 'location',
      value: '书房',
    }).snapshot;
    expect(changed).toEqual({ ...original.snapshot, location: '书房' });
    const bill = row('bills', {
      category: 'food',
      final_amount_cents: new LosslessNumber('9007199254740993'),
      settlement_status: 'refunded',
    });
    expect(organizeSnapshot('bills', bill, { kind: 'category', value: 'other' }).snapshot).toEqual({
      ...bill.snapshot,
      category: 'other',
    });
    expect(() =>
      prepareBulk(
        'stored_items',
        Array.from({ length: 51 }, (_, i) => ({ ...original, id: String(i) })),
        { kind: 'location', value: '书房' },
        'g',
      ),
    ).toThrow('bulk_limit');
  });
});
