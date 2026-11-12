import { describe, expect, it, vi } from 'vitest';
import { APIError } from './api.js';
import {
  recordReferences,
  relationProgress,
  loadRelationPage,
  relationComplete,
  recordKey,
  relationDepths,
} from './record-relations.js';
import type { Row } from './generated/api/models/Row.js';

const row = (table: string, id: string, snapshot: Record<string, unknown> = {}): Row => ({
  table,
  id,
  snapshot,
  version: 1,
  updatedAtMs: 1793923200000,
  deviceId: 'synthetic',
});
const empty = { rows: [], nextCursor: '', more: false };
const signal = () => new AbortController().signal;

describe('explicit record references', () => {
  it('uses contract dependencies for each reference family', () => {
    const cases = [
      ['bills', 'stored_value_account_id', 'stored_value_accounts'],
      ['bills', 'image_asset_id', 'media_assets'],
      ['decision_options', 'collection_id', 'decision_collections'],
      ['decision_options', 'image_asset_id', 'media_assets'],
      ['diary_links', 'diary_id', 'diary_entries'],
      ['food_entries', 'bill_id', 'bills'],
      ['media_attachments', 'asset_id', 'media_assets'],
      ['reminder_completions', 'reminder_id', 'reminders'],
      ['reminders', 'image_asset_id', 'media_assets'],
      ['stored_items', 'category_id', 'item_categories'],
      ['stored_items', 'image_asset_id', 'media_assets'],
      ['stored_value_adjustments', 'account_id', 'stored_value_accounts'],
      ['track_points', 'track_id', 'tracks'],
      ['track_segments', 'track_id', 'tracks'],
    ];
    for (const [table, field, target] of cases)
      expect(recordReferences(row(table, 'source', { [field]: 'target' }))).toEqual([
        {
          source: { table, id: 'source' },
          target: { table: target, id: 'target' },
          fields: [field],
          kind: 'current',
          savedTitle: undefined,
        },
      ]);
  });
  it('keeps historical choice and diary references separate from current dependencies', () => {
    const choice = recordReferences(
      row('decision_choice_records', 'choice', {
        collection_id: 'collection',
        option_id: 'option',
        option_title: 'Saved option',
      }),
    );
    expect(choice).toHaveLength(2);
    expect(choice.every((edge) => edge.kind === 'saved_reference')).toBe(true);
    expect(choice.find((edge) => edge.target.id === 'option')?.savedTitle).toBe('Saved option');
    const sources = {
      track: 'tracks',
      bill: 'bills',
      reminder: 'reminders',
      anniversary: 'anniversaries',
      storedItem: 'stored_items',
      decisionChoice: 'decision_choice_records',
    };
    for (const [source, table] of Object.entries(sources))
      expect(
        recordReferences(
          row('diary_links', 'link', { source_type: source, source_id: 'target' }),
        )[0],
      ).toMatchObject({ target: { table, id: 'target' }, kind: 'saved_reference' });
  });
  it('does not infer relations from tags, dates, nested snapshots or unknown types', () => {
    expect(
      recordReferences(
        row('diary_entries', 'diary', {
          tags_json: '[{"key":"tag-id","value":"value"}]',
          occurred_at: 1,
          title: 'bill-id',
        }),
      ),
    ).toEqual([]);
    expect(recordReferences(row('stored_items', 'item', { tags: 'tag-id' }))).toEqual([]);
    for (const source_type of ['future', '__proto__', 'toString'])
      expect(
        recordReferences(
          row('diary_links', 'link', {
            source_type,
            source_id: 'target',
            snapshot_json: '{"bill_id":"nested"}',
          }),
        ),
      ).toEqual([]);
    expect(
      recordReferences(row('media_attachments', 'a', { owner_type: 'future', owner_id: 'target' })),
    ).toEqual([]);
    expect(
      recordReferences(
        row('media_attachments', 'a', { owner_type: 'foodEntry', owner_id: 'food' }),
      )[0].target.table,
    ).toBe('food_entries');
  });
  it('deduplicates bill IDs and ignores malformed legacy JSON', () => {
    const edges = recordReferences(
      row('food_entries', 'food', {
        bill_id: 'bill',
        bill_ids_json: '["bill","bill","other",null,2,""]',
      }),
    );
    expect(edges).toHaveLength(2);
    expect(edges[0].fields).toEqual(['bill_id', 'bill_ids_json']);
    for (const bill_ids_json of ['broken', '{}', null, ['bill']])
      expect(recordReferences(row('food_entries', 'food', { bill_ids_json }))).toEqual([]);
  });
});

describe('bounded relationship discovery', () => {
  it('validates exact inbound IDs and types, then continues pagination fairly', async () => {
    const root = row('bills', 'bill-1');
    const client = {
      record: vi.fn(async () => root),
      records: vi.fn(async (table: string, query: Record<string, string | number | undefined>) => {
        if (table === 'food_entries' && !query.cursor)
          return {
            rows: [row(table, 'false', { bill_id: 'bill-10', note: 'bill-1' })],
            more: true,
            nextCursor: 'next',
          };
        if (table === 'food_entries')
          return { ...empty, rows: [row(table, 'food', { bill_id: 'bill-1' })] };
        return {
          ...empty,
          rows: [
            row(table, 'wrong', { source_type: 'track', source_id: 'bill-1' }),
            row(table, 'right', { source_type: 'bill', source_id: 'bill-1' }),
          ],
        };
      }),
    };
    const result = await loadRelationPage(
      root,
      relationProgress(root),
      new Set([recordKey(root)]),
      20,
      signal(),
      client,
    );
    expect(result.nodes.map((node) => node.id)).toEqual(['right', 'food']);
    expect(result.edges).toHaveLength(2);
    expect(relationComplete(result.progress)).toBe(true);
    expect(client.records.mock.calls.map(([table]) => table)).toEqual([
      'food_entries',
      'diary_links',
      'food_entries',
    ]);
    expect(client.records.mock.calls.every(([, query]) => query.q === 'bill-1')).toBe(true);
  });
  it('keeps unavailable references without treating network failures as deletion', async () => {
    const root = row('decision_choice_records', 'choice', {
      collection_id: 'gone',
      option_id: 'denied',
    });
    const client = {
      record: vi.fn(async (_table: string, id: string): Promise<Row> => {
        throw new APIError(id === 'gone' ? 404 : 403);
      }),
      records: vi.fn(async () => empty),
    };
    const result = await loadRelationPage(
      root,
      relationProgress(root),
      new Set([recordKey(root)]),
      20,
      signal(),
      client,
    );
    expect(result.nodes.map((node) => node.status)).toEqual(['unavailable', 'forbidden']);
    client.record.mockRejectedValueOnce(new TypeError('Network failed'));
    await expect(
      loadRelationPage(root, relationProgress(root), new Set(), 20, signal(), client),
    ).rejects.toBeInstanceOf(TypeError);
    client.record.mockRejectedValueOnce(new APIError(401));
    await expect(
      loadRelationPage(root, relationProgress(root), new Set(), 20, signal(), client),
    ).rejects.toMatchObject({ status: 401 });
  });
  it('preserves pending references at the node limit without mutating prior progress', async () => {
    const root = row('food_entries', 'food', { bill_ids_json: '["one","two","three"]' });
    const progress = relationProgress(root);
    const client = {
      record: vi.fn(async (table: string, id: string) => row(table, id)),
      records: vi.fn(async () => empty),
    };
    const result = await loadRelationPage(
      root,
      progress,
      new Set([recordKey(root)]),
      2,
      signal(),
      client,
    );
    expect(result.nodes).toHaveLength(2);
    expect(result.progress.outgoing.map((edge) => edge.target.id)).toEqual(['three']);
    expect(progress.outgoing).toHaveLength(3);
    expect(client.record).toHaveBeenCalledTimes(2);
  });
  it('bounds requests and rejects stuck cursors and mismatched detail responses', async () => {
    const root = row('bills', 'bill');
    let cursor = 0;
    const client = {
      record: vi.fn(async () => row('bills', 'wrong')),
      records: vi.fn(async () => ({ rows: [], more: true, nextCursor: String(++cursor) })),
    };
    const result = await loadRelationPage(
      root,
      relationProgress(root),
      new Set(),
      20,
      signal(),
      client,
    );
    expect(client.records).toHaveBeenCalledTimes(6);
    expect(relationComplete(result.progress)).toBe(false);
    client.records.mockResolvedValue({ rows: [], more: true, nextCursor: 'stuck' });
    await expect(
      loadRelationPage(root, relationProgress(root), new Set(), 20, signal(), client),
    ).rejects.toThrow('Invalid pagination cursor');
    const food = row('food_entries', 'food', { bill_id: 'bill' });
    await expect(
      loadRelationPage(food, relationProgress(food), new Set(), 20, signal(), client),
    ).rejects.toThrow('Invalid record response');
  });
  it('discards responses completed after cancellation', async () => {
    const root = row('food_entries', 'food', { bill_id: 'bill' });
    const controller = new AbortController();
    const client = {
      record: vi.fn(async () => {
        controller.abort();
        return row('bills', 'bill');
      }),
      records: vi.fn(async () => empty),
    };
    await expect(
      loadRelationPage(root, relationProgress(root), new Set(), 20, controller.signal, client),
    ).rejects.toMatchObject({ name: 'AbortError' });
  });
  it('computes shortest expansion depths across cycles and shared records', () => {
    const a = row('bills', 'a'),
      b = row('bills', 'b'),
      c = row('bills', 'c');
    const edges = [
      [a, b],
      [b, c],
      [c, a],
    ].map(([source, target]) => ({ source, target, fields: ['id'], kind: 'current' as const }));
    expect([...relationDepths(a, edges).values()]).toEqual([0, 1, 1]);
  });
});
