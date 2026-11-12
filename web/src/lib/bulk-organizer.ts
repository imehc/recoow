import { stringify } from 'lossless-json';
import { parseExactJSON } from './api.js';
import { createDraft, editedSnapshot, freezeWrite, type FrozenWrite } from './record-editor.js';
import type { Row } from './generated/api/models/Row.js';
import type { PushResult } from './generated/api/models/PushResult.js';

export const BULK_LIMIT = 50;
export type BulkAction =
  | { kind: 'add_tag' | 'remove_tag'; key: string; value: string }
  | { kind: 'location' | 'category'; value: string };
export type BulkItem = {
  row: Row;
  field: string;
  candidate?: Record<string, unknown>;
  write?: FrozenWrite;
  state:
    'ready' | 'sending' | 'saved' | 'unchanged' | 'invalid' | 'failed' | 'conflict' | 'unknown';
  error?: string;
  result?: PushResult;
};

export function organizeSnapshot(
  table: string,
  row: Row,
  action: BulkAction,
): { field: string; snapshot: Record<string, unknown> } {
  const original = row.snapshot;
  if (!original || row.deletedAtMs) throw new Error('invalid');
  const field =
    action.kind === 'location' ? 'location' : action.kind === 'category' ? 'category' : 'tags_json';
  if (
    table !==
    (field === 'location' ? 'stored_items' : field === 'category' ? 'bills' : 'diary_entries')
  )
    throw new Error('invalid');
  let next: unknown = action.value;
  if (action.kind === 'add_tag' || action.kind === 'remove_tag') {
    if (!action.key || !action.value.trim()) throw new Error('tags');
    const raw = original.tags_json;
    const tags: unknown = raw == null ? [] : typeof raw === 'string' ? parseExactJSON(raw) : null;
    if (
      !Array.isArray(tags) ||
      tags.some(
        (tag) =>
          !tag ||
          typeof tag !== 'object' ||
          typeof tag.key !== 'string' ||
          typeof tag.value !== 'string',
      )
    )
      throw new Error('tags');
    // 以标签 key 定位，保留其他对象和未知字段；相同名称的不同 key 不混为一项。
    const updated =
      action.kind === 'remove_tag'
        ? tags.filter((tag) => tag.key !== action.key)
        : tags.some((tag) => tag.key === action.key)
          ? tags
          : [...tags, { key: action.key, value: action.value }];
    if (updated.length === tags.length) return { field, snapshot: original };
    next = stringify(updated);
  }
  const initial = createDraft(table, original);
  const draft = createDraft(table, original);
  draft[field] = { mode: 'value', text: String(next) };
  return { field, snapshot: editedSnapshot(table, original, draft, initial) };
}

export function prepareBulk(
  table: string,
  rows: Row[],
  action: BulkAction,
  generation: string,
): BulkItem[] {
  if (rows.length > BULK_LIMIT || new Set(rows.map((row) => row.id)).size !== rows.length)
    throw new Error('bulk_limit');
  const operation = crypto.randomUUID();
  return rows.map((source) => {
    // 预览后刷新列表不得改变基线；回执未知时复用完全相同的请求。
    const row = parseExactJSON(stringify(source)!) as Row;
    try {
      const { field, snapshot } = organizeSnapshot(table, row, action);
      if (stringify(snapshot) === stringify(row.snapshot))
        return { row, field, state: 'unchanged' };
      return {
        row,
        field,
        candidate: snapshot,
        write: freezeWrite(
          table,
          row.id,
          generation,
          row,
          snapshot,
          'PUT',
          Date.now(),
          operation + ':' + crypto.randomUUID(),
        ),
        state: 'ready',
      };
    } catch (error) {
      return {
        row,
        field:
          action.kind === 'location'
            ? 'location'
            : action.kind === 'category'
              ? 'category'
              : 'tags_json',
        state: 'invalid',
        error: error instanceof Error ? error.message : 'invalid',
      };
    }
  });
}
