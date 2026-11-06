<script lang="ts">
  import { page } from '$app/state';
  import { goto } from '$app/navigation';
  import { createInfiniteQuery } from '@tanstack/svelte-query';
  import { api } from '#lib/api.js';
  import { entity, entityTitle, title, time, dateRange, fieldLabel } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  import { Button } from '#lib/components/ui/button/index.js';
  import DateRange from '#lib/components/DateRange.svelte';
  import Freshness from '#lib/components/Freshness.svelte';
  import QueryStatus from '#lib/components/QueryStatus.svelte';
  import PageHeader from '#lib/components/PageHeader.svelte';
  import SelectField from '#lib/components/SelectField.svelte';
  import { Search, ArrowRight } from '@lucide/svelte';
  import { recordGroups, recordGroup, groupTitle } from '#lib/record-groups.js';
  let search = $state('');
  let from = $state('');
  let to = $state('');
  let filters = $state<Record<string, string | number | undefined>>({});
  const table = $derived(page.params.table ?? '');
  const group = $derived(recordGroup(table));
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
/>
<section class="panel filter-panel" aria-label={t('filters')}>
  <form
    class="filters"
    onsubmit={(event) => {
      event.preventDefault();
      filters = { q: search.trim(), ...dateRange(from, to) };
    }}
  >
    <SelectField
      label={t('record_categories')}
      value={group?.id ?? ''}
      options={recordGroups.map((item) => ({ value: item.id, label: groupTitle(item) }))}
      onchange={(next) => {
        filters = {};
        search = '';
        from = '';
        to = '';
        const target = recordGroups.find((item) => item.id === next);
        if (target) void goto('/records/' + target.tables[0]);
      }}
    />
    <label class="form-field"
      >{t('search_b97cb8')}
      <div class="search-input">
        <Search size={16} /><input
          type="search"
          bind:value={search}
          placeholder={t('search_record_content')}
        />
      </div></label
    >
    <DateRange bind:from bind:to />
    <div class="filter-actions">
      <p class="filter-hint">{t('date_field')}: {fieldLabel(table, dateField)}</p>
      <div class="filter-buttons">
        <Button type="submit">{t('search')}</Button><Button
          variant="outline"
          onclick={() => {
            search = '';
            from = '';
            to = '';
            filters = {};
          }}>{t('reset_filters')}</Button
        >
      </div>
    </div>
  </form>
</section>
<section class="panel">
  {#if group}<nav class="record-tabs" aria-label={t('record_type')}>
      {#each group.tables as child (child)}<a
          href={'/records/' + child}
          class:active={child === table}
          aria-current={child === table ? 'page' : undefined}
          onclick={() => {
            filters = {};
            search = '';
            from = '';
            to = '';
          }}>{entityTitle(child)}</a
        >{/each}
    </nav>{/if}
  <div class="results-heading">
    <h2>{t('results')}</h2>
    <span class="result-count">{t('records_loaded', { count: rows.length })}</span>
  </div>
  {#if !entity(table)}<div class="status">{t('unknown_record_type')}</div>{:else}
    <QueryStatus
      pending={query.isPending}
      error={query.error}
      empty={!rows.length}
      retry={() => query.refetch()}
    />
    {#if rows.length}<div class="table-wrap">
        <table class="data-table">
          <thead
            ><tr
              ><th>{t('record')}</th><th>{t('client_modified_at')}</th><th>{t('cloud_version')}</th
              ><th class="row-action">{t('details')}</th></tr
            ></thead
          >
          <tbody
            >{#each rows as row (row.id)}<tr>
                <td
                  ><a
                    class="record-title"
                    href={'/records/' + table + '/' + encodeURIComponent(row.id)}
                    >{title(row.snapshot, row.id)}</a
                  >
                  <div class="record-subtitle"><span class="record-id">{row.id}</span></div></td
                >
                <td data-label={t('client_modified_at')} class="time-cell"
                  >{time(row.updatedAtMs)}</td
                ><td data-label={t('cloud_version')}>{String(row.version)}</td>
                <td class="row-action"
                  ><a
                    class="text-link"
                    href={'/records/' + table + '/' + encodeURIComponent(row.id)}
                    >{t('view_record')}<ArrowRight size={14} /></a
                  ></td
                >
              </tr>{/each}</tbody
          >
        </table>
      </div>{/if}
    <div class="table-footer">
      <Freshness at={query.dataUpdatedAt} /><Button
        variant="outline"
        disabled={!query.hasNextPage || query.isFetchingNextPage}
        onclick={() => query.fetchNextPage()}
        >{query.isFetchingNextPage
          ? t('loading')
          : query.hasNextPage
            ? t('load_more')
            : t('all_loaded')}</Button
      >
    </div>
  {/if}
</section>
