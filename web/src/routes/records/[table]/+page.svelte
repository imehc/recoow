<script lang="ts">
  import { page } from '$app/state';
  import { goto } from '$app/navigation';
  import { createInfiniteQuery } from '@tanstack/svelte-query';
  import { api } from '#lib/api.js';
  import {
    entities,
    entity,
    entityTitle,
    title,
    time,
    dateRange,
    fieldLabel,
  } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  import { Button } from '#lib/components/ui/button/index.js';
  import DateRange from '#lib/components/DateRange.svelte';
  import Freshness from '#lib/components/Freshness.svelte';
  import QueryStatus from '#lib/components/QueryStatus.svelte';
  let search = $state('');
  let from = $state('');
  let to = $state('');
  let filters = $state<Record<string, string | number | undefined>>({});
  const table = $derived(page.params.table ?? '');
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

<div class="page-heading">
  <div>
    <p class="eyebrow">{t('cloud_records_e4ffca')}</p>
    <h1>{entityTitle(table)}</h1>
    <p>
      {t('browse_synced_records_by_type_text_or_date')}
    </p>
  </div>
</div>
<section class="panel">
  <form
    class="filters"
    onsubmit={(event) => {
      event.preventDefault();
      filters = { q: search.trim(), ...dateRange(from, to) };
    }}
  >
    <label
      >{t('record_type')}<select
        aria-label={t('record_type')}
        value={table}
        onchange={(event) => {
          filters = {};
          search = '';
          from = '';
          to = '';
          void goto('/records/' + event.currentTarget.value);
        }}
        >{#each entities as item (item.table)}<option value={item.table}
            >{entityTitle(item.table)}</option
          >{/each}</select
      ></label
    ><label class="search"
      >{t('search_b97cb8')}<input
        type="search"
        bind:value={search}
        placeholder={t('search_record_content')}
      /></label
    ><DateRange bind:from bind:to /><Button variant="outline" type="submit">{t('search')}</Button>
  </form>
  <div class="panel-body freshness">
    {t('date_field')}: {fieldLabel(table, dateField)} · {t(
      'shows_current_cloud_records_refresh_to_include_changes_synced_while_pagi',
    )}
  </div>
  {#if !entity(table)}<div class="status">
      {t('unknown_record_type')}
    </div>{:else}<QueryStatus
      pending={query.isPending}
      error={query.error}
      empty={!rows.length}
      retry={() => query.refetch()}
    />
    {#if rows.length}<div class="table-wrap">
        <table>
          <thead
            ><tr
              ><th>{t('record')}</th><th>{t('client_modified_at')}</th><th>{t('cloud_version')}</th
              ><th>{t('details')}</th></tr
            ></thead
          ><tbody
            >{#each rows as row (row.id)}<tr
                ><td
                  ><a
                    class="record-title"
                    href={'/records/' + table + '/' + encodeURIComponent(row.id)}
                    >{title(row.snapshot, row.id)}</a
                  >
                  <div class="record-id">{row.id}</div></td
                ><td>{time(row.updatedAtMs)}</td><td>{String(row.version)}</td><td
                  ><a
                    class="text-link"
                    href={'/records/' + table + '/' + encodeURIComponent(row.id)}
                    >{t('view_record')}</a
                  ></td
                ></tr
              >{/each}</tbody
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
    </div>{/if}
</section>
