<script lang="ts">
  import { clientValue } from '#lib/client-values.js';
  import { parseExactJSON } from '#lib/api.js';
  import { stringify } from 'lossless-json';
  import { X } from '@lucide/svelte';
  import { entity, fieldLabel } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  import type { EditorField, FieldDraft } from '#lib/record-editor.js';
  import DateField from './DateField.svelte';
  import SelectField from './SelectField.svelte';
  import RecordReference from './RecordReference.svelte';
  import { Button } from './ui/button/index.js';
  let {
    table,
    field,
    draft = $bindable(),
    disabled = false,
    snapshot,
  }: {
    table: string;
    field: EditorField;
    draft: FieldDraft;
    disabled?: boolean;
    snapshot: Record<string, unknown>;
  } = $props();
  const id = $props.id();
  const label = $derived(fieldLabel(table, field.name));
  const dependency = $derived(
    entity(table)?.dependencies.find((item) => item.field === field.name),
  );
  const values = $derived(
    field.enumValues ??
      (field.name === 'mood'
        ? ['neutral', 'happy', 'calm', 'tired', 'stressed', 'sad']
        : undefined),
  );
  const choices = $derived(
    values
      ? [
          ...new Set([
            ...values,
            ...(draft.text && !values.includes(draft.text) ? [draft.text] : []),
          ]),
        ].map((value) => ({
          value,
          label: clientValue(table, field.name, value, snapshot) ?? value,
        }))
      : [],
  );
  const blocked = $derived(disabled || draft.mode !== 'value');
  let tagInput = $state('');
  let selectedTag = $state('');
  const tags = $derived.by(() => {
    try {
      const value = parseExactJSON(draft.text);
      return Array.isArray(value) &&
        value.every(
          (item) =>
            item &&
            typeof item === 'object' &&
            typeof item.key === 'string' &&
            typeof item.value === 'string',
        )
        ? (value as { key: string; value: string }[])
        : null;
    } catch {
      return null;
    }
  });
  function addTag() {
    if (tags && tagInput.trim() && !tags.some((tag) => tag.value === tagInput.trim()))
      draft.text =
        stringify([...tags, { key: crypto.randomUUID(), value: tagInput.trim() }]) ?? '[]';
    tagInput = '';
  }
  function setDate(date: string) {
    draft.text = date ? `${date}T${draft.text.split('T')[1] || '00:00:00.000'}` : '';
  }
</script>

<div
  class="editor-field"
  class:wide={dependency || ['content', 'note', 'refund_reason', 'tags_json'].includes(field.name)}
>
  <div class="editor-label">
    <label for={id}
      >{label}{#if field.required}<span aria-hidden="true">
          *</span
        >{/if}{#if field.format === 'amount_cents'}
        ({t('currency_yuan')}){/if}</label
    >
    {#if field.nullable}<Button
        type="button"
        variant="ghost"
        {disabled}
        aria-label={draft.mode === 'value'
          ? t('clear_field', { label })
          : t('set_field', { label })}
        onclick={() => {
          draft.mode = draft.mode === 'value' ? 'null' : 'value';
          draft.text = '';
        }}>{draft.mode === 'value' ? t('clear') : t('enter_value')}</Button
      >{/if}
  </div>
  {#if draft.mode !== 'value'}<p class="unset-value">
      {draft.mode === 'missing' ? t('missing') : t('null')}
    </p>
  {:else if dependency}<RecordReference
      table={dependency.table}
      {label}
      bind:value={draft.text}
      disabled={blocked}
    />
  {:else if choices.length}<SelectField
      {label}
      options={choices}
      bind:value={draft.text}
      disabled={blocked}
    />
  {:else if field.format === 'ts_ms'}<div class="editor-date">
      <DateField
        {label}
        value={draft.text.split('T')[0] ?? ''}
        onchange={setDate}
        disabled={blocked}
      /><label class="form-field" for={id}
        ><span class="sr-only">{t('time_of_day')}</span><input
          {id}
          type="time"
          step="0.001"
          value={draft.text.split('T')[1] ?? ''}
          disabled={blocked}
          oninput={(event) => {
            draft.text = `${draft.text.split('T')[0] || ''}T${event.currentTarget.value}`;
          }}
        /></label
      >
    </div>
  {:else if field.name === 'tags_json' && tags}<div class="tag-editor">
      <RecordReference
        table="diary_tags"
        label={t('existing_tags')}
        bind:value={selectedTag}
        disabled={blocked}
        onselect={(row) => {
          if (
            row &&
            typeof row.snapshot?.name === 'string' &&
            !tags.some((tag) => tag.key === row.id)
          )
            draft.text = stringify([...tags, { key: row.id, value: row.snapshot.name }]) ?? '[]';
        }}
      />
      <div class="tag-values">
        {#each tags as tag, index (index)}<Button
            variant="outline"
            disabled={blocked}
            aria-label={t('remove_tag', { tag: tag.value })}
            onclick={() => {
              draft.text = stringify(tags.filter((_, current) => index !== current)) ?? '[]';
            }}>{tag.value}<X size={14} /></Button
          >{/each}
      </div>
      <div class="tag-entry">
        <input
          {id}
          aria-label={label}
          bind:value={tagInput}
          disabled={blocked}
          placeholder={t('tag_name')}
          onkeydown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              addTag();
            }
          }}
        /><Button variant="outline" disabled={blocked || !tagInput.trim()} onclick={addTag}
          >{t('add_tag')}</Button
        >
      </div>
    </div>
  {:else if ['content', 'note', 'refund_reason'].includes(field.name) || field.format === 'multiline' || field.format === 'json'}<textarea
      {id}
      bind:value={draft.text}
      disabled={blocked}
      rows={field.name === 'content' ? 12 : 4}
      aria-required={field.required}></textarea>
  {:else}<input
      {id}
      bind:value={draft.text}
      disabled={blocked}
      inputmode={['int', 'real'].includes(field.type) ? 'decimal' : 'text'}
      aria-required={field.required}
    />{/if}
</div>

<style>
  .editor-field {
    display: grid;
    gap: 8px;
    min-width: 0;
    align-content: start;
  }
  .wide {
    grid-column: 1 / -1;
  }
  .editor-label {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    min-height: 24px;
  }
  .editor-label label {
    font-size: 0.8125rem;
    font-weight: 500;
  }
  .editor-label :global(button) {
    height: 24px;
    padding: 0 8px;
  }
  .editor-date {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px;
  }
  .unset-value {
    color: var(--muted-foreground);
    font-size: 0.8125rem;
    min-height: var(--control-height);
    padding: 8px 12px;
    background: var(--muted);
    border-radius: 6px;
  }
  .editor-field :global(.form-field > label),
  .editor-field :global(.date-field > .field-label) {
    display: none;
  }
  .tag-values,
  .tag-entry {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
  .tag-entry input {
    flex: 1;
    min-width: 120px;
  }
  .tag-editor {
    display: grid;
    gap: 8px;
  }
  @media (max-width: 600px) {
    .editor-date {
      grid-template-columns: 1fr;
    }
  }
</style>
