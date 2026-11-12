import { LosslessNumber, isLosslessNumber, stringify } from 'lossless-json';
import { entity, integer } from './format.js';
import { parseCloudJSON, parseExactJSON, serializeCloudJSON } from './api.js';
import type { Row } from './generated/api/models/Row.js';
import type { RecordMutation } from './generated/api/models/RecordMutation.js';
import type { RecordDeletion } from './generated/api/models/RecordDeletion.js';

export const editableTables = ['bills', 'diary_entries', 'stored_items', 'diary_tags'];
export interface EditorField {
  name: string;
  type: string;
  format: string;
  editable?: boolean;
  required?: boolean;
  nullable?: boolean;
  default?: unknown;
  enumValues?: readonly string[];
  group?: string;
}
export interface FieldDraft {
  text: string;
  mode: 'value' | 'null' | 'missing';
}
export type Draft = Record<string, FieldDraft>;
export type WriteMethod = 'POST' | 'PUT' | 'DELETE';
export interface FrozenWrite {
  table: string;
  id: string;
  method: WriteMethod;
  body: string;
}
export const editorFields = (table: string) =>
  (entity(table)?.fields as readonly EditorField[] | undefined)?.filter(
    (field) => field.editable,
  ) ?? [];

export function decimalAmount(value: unknown): string {
  const cents = integer(value);
  if (cents === null) return '';
  const absolute = cents < 0n ? -cents : cents;
  return `${cents < 0n ? '-' : ''}${absolute / 100n}.${String(absolute % 100n).padStart(2, '0')}`;
}
export function amountCents(text: string): LosslessNumber {
  if (!/^-?\d+(\.\d{1,2})?$/.test(text)) throw new Error('amount');
  const [whole, fraction = ''] = text.replace(/^-/, '').split('.');
  const cents =
    (BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))) * (text.startsWith('-') ? -1n : 1n);
  if (cents < 0n || cents > 9223372036854775807n) throw new Error('amount');
  return new LosslessNumber(String(cents));
}
export function dateTimeText(value: unknown): string {
  const timestamp = integer(value);
  if (timestamp === null || timestamp <= 0n || timestamp > 8640000000000000n) return '';
  const date = new Date(Number(timestamp));
  const pad = (value: number, length = 2) => String(value).padStart(length, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`;
}
export function fieldText(field: EditorField, value: unknown): string {
  if (value == null) return '';
  if (field.format === 'amount_cents') return decimalAmount(value);
  if (field.format === 'ts_ms') return dateTimeText(value);
  if (isLosslessNumber(value)) return value.value;
  return typeof value === 'string'
    ? value
    : typeof value === 'object'
      ? (stringify(value) ?? '')
      : String(value);
}
export function createDraft(table: string, snapshot: Record<string, unknown>): Draft {
  return Object.fromEntries(
    editorFields(table).map((field) => [
      field.name,
      {
        text: fieldText(field, snapshot[field.name]),
        mode: Object.hasOwn(snapshot, field.name)
          ? snapshot[field.name] === null
            ? 'null'
            : 'value'
          : 'missing',
      },
    ]),
  );
}
export function newSnapshot(table: string, id: string, now: number): Record<string, unknown> {
  const snapshot: Record<string, unknown> = { id, created_at: now, updated_at: now };
  for (const field of editorFields(table)) {
    if (field.default !== undefined) snapshot[field.name] = field.default;
    else if (field.required)
      snapshot[field.name] = field.format === 'ts_ms' ? now : field.type === 'int' ? 0 : '';
  }
  if (table === 'bills')
    Object.assign(snapshot, {
      transaction_type: 'expense',
      category: 'other',
      payment_method: 'other',
    });
  if (table === 'diary_entries') Object.assign(snapshot, { mood: 'neutral', tags_json: '[]' });
  return snapshot;
}
export function draftChanged(draft: Draft, initial: Draft): boolean {
  return Object.keys(draft).some(
    (key) => draft[key].text !== initial[key].text || draft[key].mode !== initial[key].mode,
  );
}
export class FieldError extends Error {
  constructor(
    public field: string,
    public reason: string,
  ) {
    super(reason);
  }
}
function inputValue(field: EditorField, text: string): unknown {
  if (field.format === 'amount_cents') return amountCents(text.trim());
  if (field.format === 'ts_ms') {
    const number = new Date(text).getTime();
    if (!Number.isSafeInteger(number) || number <= 0) throw new Error('date');
    return number;
  }
  if (field.type === 'int') {
    if (
      !/^-?\d+$/.test(text) ||
      BigInt(text) < -9223372036854775808n ||
      BigInt(text) > 9223372036854775807n
    )
      throw new Error('number');
    return new LosslessNumber(text);
  }
  if (field.type === 'real') {
    if (!/^-?(\d+\.?\d*|\.\d+)(e[+-]?\d+)?$/i.test(text) || !Number.isFinite(Number(text)))
      throw new Error('number');
    return new LosslessNumber(text);
  }
  if (field.format === 'json') {
    const value = parseExactJSON(text);
    if (
      field.name === 'tags_json' &&
      (!Array.isArray(value) ||
        !value.every(
          (tag) =>
            tag &&
            typeof tag === 'object' &&
            typeof tag.key === 'string' &&
            typeof tag.value === 'string',
        ))
    )
      throw new Error('tags');
  }
  return text;
}
export function editedSnapshot(
  table: string,
  original: Record<string, unknown>,
  draft: Draft,
  initial: Draft,
  creating = false,
): Record<string, unknown> {
  const snapshot = { ...original };
  for (const field of editorFields(table)) {
    const value = draft[field.name];
    if (value.mode === initial[field.name].mode && value.text === initial[field.name].text)
      continue;
    if (value.mode === 'missing') continue;
    if (value.mode === 'null') {
      if (field.nullable) snapshot[field.name] = null;
      else throw new FieldError(field.name, 'required');
      continue;
    }
    try {
      snapshot[field.name] = inputValue(field, value.text);
    } catch (error) {
      throw new FieldError(field.name, error instanceof Error ? error.message : 'invalid');
    }
  }
  for (const field of editorFields(table)) {
    if (
      field.required &&
      (snapshot[field.name] == null ||
        (['title', 'name'].includes(field.name) && !String(snapshot[field.name]).trim()))
    )
      throw new FieldError(field.name, 'required');
  }
  if (
    table === 'bills' &&
    snapshot.transaction_type === 'storedValueUse' &&
    !snapshot.stored_value_account_id
  )
    throw new FieldError('stored_value_account_id', 'required');
  if (
    table === 'bills' &&
    (creating ||
      [
        'original_amount_cents',
        'discount_amount_cents',
        'final_amount_cents',
        'transaction_type',
      ].some((key) => draft[key].text !== initial[key].text))
  ) {
    const original = integer(snapshot.original_amount_cents),
      discount = integer(snapshot.discount_amount_cents),
      final = integer(snapshot.final_amount_cents);
    if (
      original === null ||
      discount === null ||
      final === null ||
      original <= 0n ||
      discount < 0n ||
      final < 0n ||
      original - discount !== final ||
      (snapshot.transaction_type !== 'expense' && (discount !== 0n || final <= 0n))
    )
      throw new FieldError('final_amount_cents', 'amount_consistency');
  }
  return snapshot;
}
export function freezeWrite(
  table: string,
  id: string,
  generation: string,
  row: Row | undefined,
  snapshot: Record<string, unknown>,
  method: WriteMethod,
  now = Date.now(),
  mutationId: string = crypto.randomUUID(),
): FrozenWrite {
  const payload: RecordMutation | RecordDeletion = {
    mutationId,
    generation,
    baseVersion: row?.version ?? 0,
    dataVersion: entity(table)?.dataVersion ?? 1,
    updatedAtMs: now,
  };
  if (method !== 'DELETE') (payload as RecordMutation).snapshot = snapshot;
  return Object.freeze({ table, id, method, body: serializeCloudJSON(payload) });
}
export function resolveWrite(
  write: FrozenWrite,
  resolution: 'local' | 'remote',
  version: unknown,
  mutationId: string = crypto.randomUUID(),
): FrozenWrite {
  const payload = parseCloudJSON(write.body) as Record<string, unknown>;
  // 原请求已经得到冲突回执；人工确认是一项新操作，基础版本与候选内容保持不变。
  return Object.freeze({
    ...write,
    body: serializeCloudJSON({ ...payload, mutationId, resolution, expectedVersion: version }),
  });
}
export function changedFields(
  table: string,
  ...snapshots: (Record<string, unknown> | undefined | null)[]
): string[] {
  const keys = new Set(snapshots.flatMap((snapshot) => Object.keys(snapshot ?? {})));
  const system = new Set([
    '_media',
    ...((entity(table)?.fields as readonly (EditorField & { system?: boolean })[]) ?? [])
      .filter((field) => field.system)
      .map((field) => field.name),
  ]);
  return [...keys].filter(
    (key) =>
      !system.has(key) &&
      new Set(
        snapshots.map((snapshot) =>
          Object.hasOwn(snapshot ?? {}, key) ? stringify(snapshot?.[key]) : undefined,
        ),
      ).size > 1,
  );
}
