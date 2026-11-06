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
    <p class="eyebrow">{t('云端记录', 'CLOUD RECORDS')}</p>
    <h1>{entityTitle(table)}</h1>
    <p>
      {t(
        '查看已同步记录；按实体类型、文字或日期筛选。',
        'Browse synced records by type, text or date.',
      )}
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
      >{t('记录类型', 'Record type')}<select
        aria-label={t('记录类型', 'Record type')}
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
      >{t('搜索', 'Search')}<input
        type="search"
        bind:value={search}
        placeholder={t('搜索记录内容', 'Search record content')}
      /></label
    ><DateRange bind:from bind:to /><Button variant="outline" type="submit"
      >{t('查询', 'Search')}</Button
    >
  </form>
  <div class="panel-body freshness">
    {t('日期依据', 'Date field')}: {fieldLabel(table, dateField)} · {t(
      '显示当前云端记录，翻页期间有新同步时可刷新获取最新结果。',
      'Shows current cloud records. Refresh to include changes synced while paging.',
    )}
  </div>
  {#if !entity(table)}<div class="status">
      {t('未知的记录类型', 'Unknown record type')}
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
              ><th>{t('记录', 'Record')}</th><th>{t('客户端修改时间', 'Client modified at')}</th><th
                >{t('云端版本', 'Cloud version')}</th
              ><th>{t('详情', 'Details')}</th></tr
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
                    >{t('查看记录', 'View record')}</a
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
          ? t('正在加载…', 'Loading…')
          : query.hasNextPage
            ? t('加载更多', 'Load more')
            : t('已显示全部', 'All loaded')}</Button
      >
    </div>{/if}
</section>
