import { describe, it, expect } from 'vitest';
import { parseCloudJSON, parseExactJSON } from './api.js';
import {
  amountCents,
  decimalAmount,
  createDraft,
  editedSnapshot,
  freezeWrite,
  resolveWrite,
  newSnapshot,
} from './record-editor.js';
import type { Row } from './generated/api/models/Row.js';

describe('online record editing', () => {
  it('retains unknown fields, numeric precision, nulls, missing fields and unedited timestamps', () => {
    const original = parseExactJSON(
      '{"id":"tag","created_at":1000,"updated_at":1000,"name":"old","note":null,"unknown":{"amount":9007199254740993,"accountId":"opaque"}}',
    ) as Record<string, unknown>;
    const initial = createDraft('diary_tags', original),
      draft = createDraft('diary_tags', original);
    draft.name.text = 'edited';
    const snapshot = editedSnapshot('diary_tags', original, draft, initial);
    const row: Row = {
      table: 'diary_tags',
      id: 'tag',
      version: 9007199254740991,
      updatedAtMs: 1000,
      deviceId: 'd',
      snapshot: original,
    };
    const write = freezeWrite('diary_tags', 'tag', 'g', row, snapshot, 'PUT', 2000, 'fixed');
    expect(write.body).toContain('9007199254740993');
    expect(write.body).toContain('"accountId":"opaque"');
    const payload = parseCloudJSON(write.body) as { snapshot: Record<string, unknown> };
    expect(payload.snapshot).toMatchObject({ name: 'edited', note: null, created_at: 1000 });
    expect(original.name).toBe('old');
    expect(Object.hasOwn(payload.snapshot, 'deleted_at')).toBe(false);
  });
  it('uses exact integer cents and rejects unsupported precision and overflow', () => {
    expect(amountCents('90071992547409.93').value).toBe('9007199254740993');
    expect(decimalAmount(amountCents('90071992547409.93'))).toBe('90071992547409.93');
    for (const value of ['1.001', '-1', '1e5', '92233720368547758.08'])
      expect(() => amountCents(value)).toThrow();
  });
  it('freezes the request body and creates a new confirmation retaining the original base and content', () => {
    const snapshot = { id: 'tag', name: 'local', future: null };
    const row: Row = {
      table: 'diary_tags',
      id: 'tag',
      version: 2,
      updatedAtMs: 1000,
      deviceId: 'd',
    };
    const frozen = freezeWrite('diary_tags', 'tag', 'g', row, snapshot, 'PUT', 2000, 'first');
    snapshot.name = 'later';
    row.version = 99;
    expect(frozen.body).toContain('"name":"local"');
    const confirmed = parseCloudJSON(
      resolveWrite(frozen, 'remote', 5, 'confirmation').body,
    ) as Record<string, unknown>;
    expect(confirmed).toMatchObject({
      mutationId: 'confirmation',
      baseVersion: 2,
      expectedVersion: 5,
      resolution: 'remote',
      updatedAtMs: 2000,
      snapshot: { name: 'local' },
    });
    expect(frozen.body).not.toContain('resolution');
  });
  it('validates bill settlement amounts and stored-value dependencies without rewriting old content', () => {
    const original = newSnapshot('bills', 'bill', 1000);
    const draft = createDraft('bills', original),
      initial = createDraft('bills', original);
    draft.title.text = 'Bill';
    draft.original_amount_cents.text = '10.00';
    draft.discount_amount_cents.text = '2.00';
    draft.final_amount_cents.text = '8.00';
    expect(
      editedSnapshot('bills', original, draft, initial, true).final_amount_cents,
    ).toMatchObject({ value: '800' });
    draft.final_amount_cents.text = '9.00';
    expect(() => editedSnapshot('bills', original, draft, initial, true)).toThrow(
      'amount_consistency',
    );
    draft.transaction_type.text = 'storedValueUse';
    expect(() => editedSnapshot('bills', original, draft, initial, true)).toThrow('required');
  });
  it('keeps saved tag references and writes new tag objects compatible with mobile clients', () => {
    const original = {
      ...newSnapshot('diary_entries', 'diary', 1000),
      title: 'Diary',
      tags_json: '[{"key":"t","value":"Saved","future":9007199254740993}]',
    };
    const draft = createDraft('diary_entries', original),
      initial = createDraft('diary_entries', original);
    draft.content.text = 'Edited';
    expect(editedSnapshot('diary_entries', original, draft, initial).tags_json).toBe(
      original.tags_json,
    );
    draft.tags_json.text = '["wrong"]';
    expect(() => editedSnapshot('diary_entries', original, draft, initial)).toThrow('tags');
  });
});
