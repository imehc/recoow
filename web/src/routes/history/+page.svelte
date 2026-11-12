<script lang="ts">
  import { page } from '$app/state';
  import { createInfiniteQuery, createQuery } from '@tanstack/svelte-query';
  import { api } from '#lib/api.js';
  import { session } from '#lib/session.svelte.js';
  import { t } from '#lib/preferences.svelte.js';
  import { dateRange, operationText, entityTitle } from '#lib/format.js';
  import { Button } from '#lib/components/ui/button/index.js';
  import DateRange from '#lib/components/DateRange.svelte';
  import Freshness from '#lib/components/Freshness.svelte';
  import HistoryList from '#lib/components/HistoryList.svelte';
  import PageHeader from '#lib/components/PageHeader.svelte';
  import SelectField from '#lib/components/SelectField.svelte';
  import { Collapsible } from 'bits-ui';
  import { ChevronDown } from '@lucide/svelte';
  import { recordGroups, groupTitle } from '#lib/record-groups.js';
  import QueryStatus from '#lib/components/QueryStatus.svelte';
  let table = $state('');
  let id = $state('');
  let client = $state('');
  let operation = $state('');
  let deviceId = $state('');
  let from = $state('');
  let to = $state('');
  let filters = $state<Record<string, string | number | undefined>>({});
  let advancedOpen = $state(false);
  $effect(() => {
    const nextTable = page.url.searchParams.get('table') ?? '';
    const nextId = page.url.searchParams.get('id') ?? '';
    table = nextTable;
    id = nextId;
    advancedOpen = !!nextId;
    filters = { table: nextTable, id: nextTable ? nextId : '' };
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

<PageHeader
  title={t('change_history')}
  description={t('see_what_changed_when_it_changed_and_which_devices_edited_and_submitted')}
/>
{#if !session.historySupported}
  <div class="notice">
    {t('change_history_is_unavailable_on_this_server')}
  </div>
{:else}
  <section class="panel filter-panel" aria-label={t('filters')}>
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
      <SelectField
        label={t('record_type')}
        bind:value={table}
        options={[
          { value: '', label: t('all') },
          ...recordGroups.flatMap((group) =>
            group.tables.map((table) => ({
              value: table,
              label: entityTitle(table),
              group: groupTitle(group),
            })),
          ),
        ]}
      />
      <SelectField
        label={t('original_platform')}
        bind:value={client}
        options={[
          { value: '', label: t('all') },
          { value: 'ios', label: 'iOS App' },
          { value: 'android', label: 'Android App' },
          { value: 'web', label: 'Web' },
          { value: 'unknown', label: t('unknown') },
        ]}
      />
      <DateRange bind:from bind:to />
      <p class="filter-hint overview-hint">{t('history_date_hint')}</p>
      <Collapsible.Root bind:open={advancedOpen} class="advanced-filters">
        <div class="filter-actions">
          <Collapsible.Trigger class="advanced-toggle">
            {t('advanced_filters')}
            <ChevronDown size={14} />
          </Collapsible.Trigger>
          <div class="filter-buttons">
            <Button type="submit">{t('search')}</Button>
            <Button
              variant="outline"
              onclick={() => {
                table = '';
                id = '';
                client = '';
                operation = '';
                deviceId = '';
                from = '';
                to = '';
                filters = {};
              }}
            >
              {t('reset_filters')}
            </Button>
          </div>
        </div>
        <Collapsible.Content>
          <div class="advanced-grid">
            <label class="form-field">
              {t('record_id')}
              <input bind:value={id} disabled={!table} />
            </label>
            <SelectField
              label={t('change')}
              bind:value={operation}
              options={[
                { value: '', label: t('all') },
                ...['insert', 'update', 'delete', 'restore'].map((op) => ({
                  value: op,
                  label: operationText(op),
                })),
              ]}
            />
            <SelectField
              label={t('submitting_device')}
              bind:value={deviceId}
              options={[
                { value: '', label: t('all') },
                ...(devices.data?.devices ?? []).map((device) => ({
                  value: device.id,
                  label: device.name || device.id,
                })),
              ]}
            />
          </div>
        </Collapsible.Content>
      </Collapsible.Root>
    </form>
  </section>
  <section class="panel">
    <div class="results-heading">
      <h2>{t('results')}</h2>
      <span class="result-count">{t('events_loaded', { count: events.length })}</span>
    </div>
    <QueryStatus
      pending={query.isPending}
      error={query.error}
      empty={!events.length}
      retry={() => query.refetch()}
    />
    {#if events.length}
      <HistoryList {events} />
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
  </section>
{/if}
