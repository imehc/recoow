<script lang="ts">
  import { appPath } from '#lib/paths.js';
  import { page } from '$app/state';
  import { goto } from '$app/navigation';
  import { createInfiniteQuery } from '@tanstack/svelte-query';
  import { api } from '#lib/api.js';
  import { session } from '#lib/session.svelte.js';
  import {
    entity,
    entityTitle,
    title,
    time,
    dateRange,
    fieldLabel,
    valueText,
  } from '#lib/format.js';
  import { recordFilters, filterURL, filterDate } from '#lib/record-filters.js';
  import WebAccessHint from '#lib/components/WebAccessHint.svelte';
  import ReferenceLabel from '#lib/components/ReferenceLabel.svelte';
  import BulkOrganizer from '#lib/components/BulkOrganizer.svelte';
  import { BULK_LIMIT } from '#lib/bulk-organizer.js';
  import type { Row } from '#lib/generated/api/models/Row.js';
  import { t } from '#lib/preferences.svelte.js';
  import { Button } from '#lib/components/ui/button/index.js';
  import DateRange from '#lib/components/DateRange.svelte';
  import Freshness from '#lib/components/Freshness.svelte';
  import QueryStatus from '#lib/components/QueryStatus.svelte';
  import PageHeader from '#lib/components/PageHeader.svelte';
  import SelectField from '#lib/components/SelectField.svelte';
  import { Search, ArrowRight, Plus } from '@lucide/svelte';
  import { recordGroups, recordGroup, groupTitle } from '#lib/record-groups.js';
  let search = $state('');
  let from = $state('');
  let to = $state('');
  const filters = $derived(recordFilters(page.url.searchParams));
  const table = $derived(page.params.table ?? '');
  let selected = $state<string[]>([]);
  let bulkRows = $state<Row[]>([]);
  const canOrganize = $derived(
    ['diary_entries', 'stored_items', 'bills'].includes(table) && session.canWrite(table),
  );
  $effect(() => {
    if (page.url.href && table) {
      selected = [];
      bulkRows = [];
    }
  });
  const group = $derived(recordGroup(table));
  $effect(() => {
    search = String(filters.q ?? '');
    from = filterDate(filters.fromMs);
    to = filterDate(filters.toMs, true);
  });
  const businessFields = $derived(
    table === 'bills'
      ? ['final_amount_cents', 'transaction_type', 'category', 'occurred_at']
      : table === 'stored_items'
        ? ['location', 'category_id']
        : [],
  );
  const columnLabel = (key: string) => fieldLabel(table, key === 'category_id' ? 'category' : key);
  const rowValue = (snapshot: Record<string, unknown> | null | undefined, key: string) =>
    valueText(table, key, snapshot?.[key], !!snapshot && Object.hasOwn(snapshot, key), snapshot);
  const dateField = $derived(
    ['occurred_at', 'started_at', 'selected_at', 'day_start_at'].find((key) =>
      entity(table)?.fields.some((field) => field.name === key),
    ) ?? 'server_updated_at_ms',
  );
  const query = createInfiniteQuery(() => ({
    queryKey: ['records', table, filters],
    queryFn: ({ signal, pageParam }) =>
      api.records(table, { ...filters, limit: 50, cursor: pageParam }, signal),
    initialPageParam: '',
    getNextPageParam: (last) => (last.more ? last.nextCursor : undefined),
    enabled: !!entity(table),
  }));
  const rows = $derived(query.data?.pages.flatMap((part) => part.rows) ?? []);
</script>

<PageHeader
  title={entityTitle(table)}
  description={t('browse_synced_records_by_type_text_or_date')}
>
  {#snippet actions()}
    {#if session.canWrite(table)}
      <Button href={appPath('/records/' + table + '/new')}>
        <Plus size={16} />
        {t('new_record')}
      </Button>
    {/if}
  {/snippet}
</PageHeader>
<WebAccessHint {table} />
<section class="panel filter-panel" aria-label={t('filters')}>
  <form
    class="filters"
    onsubmit={(event) => {
      event.preventDefault();
      const range =
        from === filterDate(filters.fromMs) && to === filterDate(filters.toMs, true)
          ? { fromMs: filters.fromMs, toMs: filters.toMs }
          : dateRange(from, to);
      void goto(
        appPath('/records/' + table) + filterURL({ ...filters, q: search.trim(), ...range }),
      );
    }}
  >
    <SelectField
      label={t('record_categories')}
      value={group?.id ?? ''}
      options={recordGroups.map((item) => ({ value: item.id, label: groupTitle(item) }))}
      onchange={(next) => {
        search = '';
        from = '';
        to = '';
        const target = recordGroups.find((item) => item.id === next);
        if (target) void goto(appPath('/records/' + target.tables[0]));
      }}
    />
    <label class="form-field">
      {t('search_b97cb8')}
      <div class="search-input">
        <Search size={16} />
        <input type="search" bind:value={search} placeholder={t('search_record_content')} />
      </div>
    </label>
    <DateRange bind:from bind:to />
    <div class="filter-actions">
      <p class="filter-hint">{t('date_field')}: {fieldLabel(table, dateField)}</p>
      <div class="filter-buttons">
        <Button type="submit">{t('search')}</Button>
        <Button
          variant="outline"
          onclick={() => {
            search = '';
            from = '';
            to = '';
            void goto(appPath('/records/' + table));
          }}
        >
          {t('reset_filters')}
        </Button>
      </div>
    </div>
  </form>
</section>
<section class="panel">
  {#if group}
    <nav class="record-tabs" aria-label={t('record_type')}>
      {#each group.tables as child (child)}
        <a
          href={appPath('/records/' + child)}
          class:active={child === table}
          aria-current={child === table ? 'page' : undefined}
          onclick={() => {
            search = '';
            from = '';
            to = '';
          }}
        >
          {entityTitle(child)}
        </a>
      {/each}
    </nav>
  {/if}
  <div class="results-heading">
    <h2>{t('results')}</h2>
    <span class="result-count">{t('records_loaded', { count: rows.length })}</span>
  </div>
  {#if canOrganize}
    <div class="bulk-selection-bar">
      <label>
        <input
          type="checkbox"
          aria-label={t('select_loaded_records')}
          checked={!!rows.length &&
            rows.slice(0, BULK_LIMIT).every((row) => selected.includes(row.id))}
          disabled={!rows.length || !!bulkRows.length}
          onchange={(event) => {
            selected = event.currentTarget.checked
              ? rows.slice(0, BULK_LIMIT).map((row) => row.id)
              : [];
          }}
        />
        {t('bulk_selected', { count: selected.length })}
      </label>
      <Button
        variant="outline"
        disabled={!selected.length || !!bulkRows.length}
        onclick={() => {
          bulkRows = rows.filter((row) => selected.includes(row.id));
        }}
      >
        {t('bulk_organize')}
      </Button>
      <span class="muted">{t('bulk_limit_hint')}</span>
    </div>
  {/if}
  {#if bulkRows.length}
    {#key table}
      <BulkOrganizer
        {table}
        rows={bulkRows}
        onclose={() => {
          bulkRows = [];
          selected = [];
        }}
      />
    {/key}
  {/if}
  {#if filters.transaction_type || filters.category !== undefined || filters.exclude_refunded}
    <p class="active-filters">
      {filters.transaction_type
        ? valueText('bills', 'transaction_type', filters.transaction_type)
        : ''}
      {#if filters.category !== undefined}
        <span>
          {fieldLabel('bills', 'category')}: {filters.category === ''
            ? t('uncategorized')
            : valueText('bills', 'category', filters.category)}
        </span>
      {/if}
      {#if filters.exclude_refunded}
        <span>{t('refunds_excluded')}</span>
      {/if}
    </p>
  {/if}
  {#if !entity(table)}
    <div class="status">{t('unknown_record_type')}</div>
  {:else}
    <QueryStatus
      pending={query.isPending}
      error={query.error}
      empty={!rows.length}
      retry={() => query.refetch()}
    />
    {#if rows.length}
      <div class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              {#if canOrganize}
                <th class="selection-cell">{t('select_records')}</th>
              {/if}
              <th>{t('record')}</th>
              {#each businessFields as key (key)}
                <th>{columnLabel(key)}</th>
              {/each}
              <th>{t('client_modified_at')}</th>
              <th>{t('cloud_version')}</th>
              <th class="row-action">{t('details')}</th>
            </tr>
          </thead>
          <tbody>
            {#each rows as row (row.id)}
              <tr>
                {#if canOrganize}
                  <td class="selection-cell" data-label={t('select_records')}>
                    <input
                      type="checkbox"
                      aria-label={t('select_record_named', { name: title(row.snapshot, row.id) })}
                      checked={selected.includes(row.id)}
                      disabled={!!bulkRows.length ||
                        (selected.length >= BULK_LIMIT && !selected.includes(row.id))}
                      onchange={(event) => {
                        selected = event.currentTarget.checked
                          ? [...selected, row.id]
                          : selected.filter((id) => id !== row.id);
                      }}
                    />
                  </td>
                {/if}
                <td>
                  <a
                    class="record-title"
                    href={appPath('/records/' + table + '/' + encodeURIComponent(row.id))}
                  >
                    {title(row.snapshot, row.id)}
                  </a>
                  <div class="record-subtitle">
                    <span class="record-id">{row.id}</span>
                  </div>
                </td>
                {#each businessFields as key (key)}
                  <td
                    data-label={columnLabel(key)}
                    class:time-cell={key === 'occurred_at'}
                    class:amount-cell={key === 'final_amount_cents'}
                  >
                    {#if key === 'category_id' && typeof row.snapshot?.category_id === 'string' && row.snapshot.category_id}
                      <ReferenceLabel table="item_categories" id={row.snapshot.category_id} />
                    {:else}
                      {rowValue(row.snapshot, key)}
                    {/if}
                  </td>
                {/each}
                <td data-label={t('client_modified_at')} class="time-cell">
                  {time(row.updatedAtMs)}
                </td>
                <td data-label={t('cloud_version')}>{String(row.version)}</td>
                <td class="row-action">
                  <a
                    class="text-link"
                    href={appPath('/records/' + table + '/' + encodeURIComponent(row.id))}
                  >
                    {t('view_record')}
                    <ArrowRight size={14} />
                  </a>
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    {/if}
    <div class="table-footer">
      <Freshness at={query.dataUpdatedAt} />
      <Button
        variant="outline"
        disabled={!query.hasNextPage || query.isFetchingNextPage}
        onclick={() => query.fetchNextPage()}
      >
        {query.isFetchingNextPage
          ? t('loading')
          : query.hasNextPage
            ? t('load_more')
            : t('all_loaded')}
      </Button>
    </div>
  {/if}
</section>
