<script lang="ts">
  import { onMount } from 'svelte';
  import { api } from '#lib/api.js';
  import { title } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  import SelectField from './SelectField.svelte';
  import QueryStatus from './QueryStatus.svelte';
  import { Button } from './ui/button/index.js';
  import type { Row } from '#lib/generated/api/models/Row.js';
  let {
    table,
    label,
    value = $bindable(''),
    disabled = false,
    onselect,
  }: {
    table: string;
    label: string;
    value?: string;
    disabled?: boolean;
    onselect?: (row: Row | undefined) => void;
  } = $props();
  let rows = $state<Row[]>([]);
  let search = $state('');
  let cursor = $state('');
  let more = $state(false);
  let pending = $state(false);
  let error = $state<Error | null>(null);
  let controller: AbortController | undefined;
  const options = $derived([
    ...(value && !rows.some((row) => row.id === value) ? [{ value, label: value }] : []),
    ...rows.map((row) => ({ value: row.id, label: `${title(row.snapshot, row.id)} · ${row.id}` })),
  ]);
  async function load(append = false) {
    controller?.abort();
    const current = new AbortController();
    controller = current;
    pending = true;
    error = null;
    try {
      const result = await api.records(
        table,
        { q: search.trim(), limit: 50, cursor: append ? cursor : '' },
        current.signal,
      );
      current.signal.throwIfAborted();
      rows = append
        ? [...rows, ...result.rows.filter((row) => !rows.some((old) => old.id === row.id))]
        : result.rows;
      cursor = result.nextCursor ?? '';
      more = result.more;
    } catch (failure) {
      if (!current.signal.aborted) error = failure as Error;
    } finally {
      if (!current.signal.aborted) pending = false;
    }
  }
  onMount(() => {
    void load();
    return () => controller?.abort();
  });
</script>

<div class="reference-input">
  <SelectField
    {label}
    bind:value
    {options}
    disabled={disabled || pending}
    onchange={(next) => onselect?.(rows.find((row) => row.id === next))}
  />
  <div class="reference-search">
    <input
      aria-label={t('search_reference', { label })}
      bind:value={search}
      {disabled}
      placeholder={t('search_record_content')}
      onkeydown={(event) => {
        if (event.key === 'Enter') {
          event.preventDefault();
          void load();
        }
      }}
    />
    <Button variant="outline" disabled={disabled || pending} onclick={() => load()}
      >{t('search')}</Button
    >
    {#if more}<Button variant="outline" disabled={disabled || pending} onclick={() => load(true)}
        >{t('load_more')}</Button
      >{/if}
  </div>
  {#if pending}<p class="muted" role="status">{t('loading')}</p>{:else if error}<QueryStatus
      {error}
      retry={() => load()}
    />{:else if !rows.length}<p class="muted">{t('no_matching_references')}</p>{/if}
</div>

<style>
  .reference-input {
    display: grid;
    gap: 8px;
  }
  .reference-search {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
  .reference-search input {
    flex: 1;
    min-width: 120px;
  }
  .reference-input p {
    font-size: var(--text-metadata, 0.75rem);
    overflow-wrap: anywhere;
  }
</style>
