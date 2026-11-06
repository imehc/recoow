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
    <p class="eyebrow">{t('committed_changes')}</p>
    <h1>{t('change_history')}</h1>
    <p>
      {t('see_what_changed_when_it_changed_and_which_devices_edited_and_submitted')}
    </p>
  </div>
</div>
{#if !session.historySupported}<div class="notice">
    {t('change_history_is_unavailable_on_this_server')}
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
        >{t('record_type')}<select aria-label={t('record_type')} bind:value={table}
          ><option value="">{t('all')}</option>{#each entities as item (item.table)}<option
              value={item.table}>{entityTitle(item.table)}</option
            >{/each}</select
        ></label
      ><label>{t('record_id')}<input bind:value={id} disabled={!table} /></label><label
        >{t('change')}<select aria-label={t('change')} bind:value={operation}
          ><option value="">{t('all')}</option
          >{#each ['insert', 'update', 'delete', 'restore'] as op (op)}<option value={op}
              >{operationText(op)}</option
            >{/each}</select
        ></label
      ><label
        >{t('original_platform')}<select aria-label={t('original_platform')} bind:value={client}
          ><option value="">{t('all')}</option><option value="ios">iOS App</option><option
            value="android">Android App</option
          ><option value="web">Web</option><option value="unknown">{t('unknown')}</option></select
        ></label
      ><label
        >{t('submitting_device')}<select aria-label={t('submitting_device')} bind:value={deviceId}
          ><option value="">{t('all')}</option
          >{#each devices.data?.devices ?? [] as device (device.id)}<option value={device.id}
              >{device.name || device.id}</option
            >{/each}</select
        ></label
      ><DateRange bind:from bind:to /><Button type="submit" variant="outline">{t('search')}</Button>
    </form>
    <div class="panel-body freshness">
      {t('dates_filter_server_acceptance_time_changes_are_ordered_by_cloud_revisio')}
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
          ? t('loading')
          : query.hasNextPage
            ? t('load_more')
            : t('all_loaded')}</Button
      >
    </div>
  </section>{/if}
