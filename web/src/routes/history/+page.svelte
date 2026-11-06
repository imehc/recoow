<script lang="ts">
  import { page } from '$app/state';
  import { createInfiniteQuery, createQuery } from '@tanstack/svelte-query';
  import { api } from '#lib/api.js';
  import { session } from '#lib/session.svelte.js';
  import { t } from '#lib/preferences.svelte.js';
  import { entities, entityTitle, dateRange, operationText } from '#lib/format.js';
  import { Button } from '#lib/components/ui/button/index.js';
  import DateRange from '#lib/components/DateRange.svelte';
  import Freshness from '#lib/components/Freshness.svelte';
  import HistoryList from '#lib/components/HistoryList.svelte';
  import QueryStatus from '#lib/components/QueryStatus.svelte';
  let table = $state('');
  let id = $state('');
  let client = $state('');
  let operation = $state('');
  let deviceId = $state('');
  let from = $state('');
  let to = $state('');
  let filters = $state<Record<string, string | number | undefined>>({});
  $effect(() => {
    table = page.url.searchParams.get('table') ?? '';
    id = page.url.searchParams.get('id') ?? '';
    filters = { table, id: table ? id : '' };
  });
  const devices = createQuery(() => ({
    queryKey: ['devices'],
    queryFn: ({ signal }) => api.devices(signal),
  }));
  const query = createInfiniteQuery(() => ({
    queryKey: ['history', filters, session.generation],
    queryFn: ({ signal, pageParam }) =>
      api.history(
        { ...filters, generation: session.generation, limit: 50, cursor: pageParam },
        signal,
      ),
    initialPageParam: '',
    getNextPageParam: (last) => (last.more ? (last.nextCursor ?? undefined) : undefined),
    enabled: session.historySupported,
  }));
  const events = $derived(query.data?.pages.flatMap((part) => part.events) ?? []);
</script>

<div class="page-heading">
  <div>
    <p class="eyebrow">{t('已提交的变化', 'COMMITTED CHANGES')}</p>
    <h1>{t('变更历史', 'Change history')}</h1>
    <p>
      {t(
        '查看什么时候改了什么、从哪台设备修改和提交。',
        'See what changed, when it changed, and which devices edited and submitted it.',
      )}
    </p>
  </div>
</div>
{#if !session.historySupported}<div class="notice">
    {t('当前服务端未开放变更历史', 'Change history is unavailable on this server.')}
  </div>{:else}<section class="panel">
    <form
      class="filters"
      onsubmit={(event) => {
        event.preventDefault();
        filters = {
          table,
          id: table ? id.trim() : '',
          client,
          operation,
          deviceId,
          ...dateRange(from, to),
        };
      }}
    >
      <label
        >{t('记录类型', 'Record type')}<select
          aria-label={t('记录类型', 'Record type')}
          bind:value={table}
          ><option value="">{t('全部', 'All')}</option>{#each entities as item (item.table)}<option
              value={item.table}>{entityTitle(item.table)}</option
            >{/each}</select
        ></label
      ><label>{t('记录 ID', 'Record ID')}<input bind:value={id} disabled={!table} /></label><label
        >{t('操作', 'Change')}<select aria-label={t('操作', 'Change')} bind:value={operation}
          ><option value="">{t('全部', 'All')}</option
          >{#each ['insert', 'update', 'delete', 'restore'] as op (op)}<option value={op}
              >{operationText(op)}</option
            >{/each}</select
        ></label
      ><label
        >{t('原始修改平台', 'Original platform')}<select
          aria-label={t('原始修改平台', 'Original platform')}
          bind:value={client}
          ><option value="">{t('全部', 'All')}</option><option value="ios">iOS App</option><option
            value="android">Android App</option
          ><option value="web">Web</option><option value="unknown">{t('未知', 'Unknown')}</option
          ></select
        ></label
      ><label
        >{t('提交设备', 'Submitting device')}<select
          aria-label={t('提交设备', 'Submitting device')}
          bind:value={deviceId}
          ><option value="">{t('全部', 'All')}</option
          >{#each devices.data?.devices ?? [] as device (device.id)}<option value={device.id}
              >{device.name || device.id}</option
            >{/each}</select
        ></label
      ><DateRange bind:from bind:to /><Button type="submit" variant="outline"
        >{t('查询', 'Search')}</Button
      >
    </form>
    <div class="panel-body freshness">
      {t(
        '日期按服务端接受时间筛选；列表按云端提交顺序排列。客户端时间来自设备，可能存在偏差。翻页保留首次查询范围，刷新可查看新变化。',
        'Dates filter server acceptance time. Changes are ordered by cloud revision. Client times may differ from the server. Pagination keeps the initial query range; refresh to see new changes.',
      )}
    </div>
    <QueryStatus
      pending={query.isPending}
      error={query.error}
      empty={!events.length}
      retry={() => query.refetch()}
    />{#if events.length}<HistoryList {events} />{/if}
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
    </div>
  </section>{/if}
