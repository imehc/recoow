<script lang="ts">
  import { createQuery } from '@tanstack/svelte-query';
  import { Wallet, ArrowDownLeft, ArrowUpRight, Files } from '@lucide/svelte';
  import { api } from '#lib/api.js';
  import { session } from '#lib/session.svelte.js';
  import { t } from '#lib/preferences.svelte.js';
  import { dateRange, integer, money } from '#lib/format.js';
  import { Button } from '#lib/components/ui/button/index.js';
  import DateRange from '#lib/components/DateRange.svelte';
  import Freshness from '#lib/components/Freshness.svelte';
  import QueryStatus from '#lib/components/QueryStatus.svelte';
  import CountChart from '#lib/components/CountChart.svelte';
  import RecentHistory from '#lib/components/RecentHistory.svelte';
  let from = $state('');
  let to = $state('');
  let range = $state<Record<string, number | undefined>>({});
  const stats = createQuery(() => ({
    queryKey: ['statistics', range],
    queryFn: ({ signal }) => api.statistics(range, signal),
  }));
  const recent = createQuery(() => ({
    queryKey: ['recent-history', session.generation],
    queryFn: ({ signal }) => api.history({ limit: 5, generation: session.generation }, signal),
    enabled: session.historySupported,
  }));
  const total = $derived(
    Object.values(stats.data?.recordCounts ?? {}).reduce(
      (sum, value) => sum + (integer(value) ?? 0n),
      0n,
    ),
  );
</script>

<div class="page-heading">
  <div>
    <p class="eyebrow">{t('your_cloud_space_58c2d0')}</p>
    <h1>{t('record_overview')}</h1>
    <p>{t('explore_your_synced_records_and_how_they_changed')}</p>
  </div>
</div>
<section class="panel" style="margin-bottom: 1.4rem">
  <form
    class="filters"
    onsubmit={(event) => {
      event.preventDefault();
      range = dateRange(from, to);
    }}
  >
    <DateRange bind:from bind:to /><Button type="submit" variant="outline"
      >{t('apply_date_range')}</Button
    >
  </form>
  <div class="panel-body freshness">
    {t('counts_use_client_modification_time_amounts_use_the_bill_date_and_exclud')}
  </div>
</section>
<QueryStatus pending={stats.isPending} error={stats.error} retry={() => stats.refetch()} />
{#if stats.data}<div class="metrics">
    {#each [{ label: t('total_records'), value: String(total), icon: Files }, { label: t('bill_expenses'), value: money(stats.data.billExpenseCents), icon: ArrowUpRight }, { label: t('bill_income'), value: money(stats.data.billIncomeCents), icon: ArrowDownLeft }, { label: t('stored_value_used'), value: money(stats.data.billStoredValueUseCents), icon: Wallet }] as metric (metric.label)}<div
        class="panel metric"
      >
        <div class="metric-icon"><metric.icon size={19} /></div>
        <div class="metric-label">{metric.label}</div>
        <div class="metric-value">{metric.value}</div>
        <div class="metric-note">{t('selected_date_range')}</div>
      </div>{/each}
  </div>
  <div class="overview-grid">
    <section class="panel">
      <div class="panel-heading"><h2>{t('records_by_type')}</h2></div>
      <CountChart counts={stats.data.recordCounts} />
    </section>
    <section class="panel">
      <div class="panel-heading">
        <h2>{t('recent_cloud_changes')}</h2>
        <a class="text-link" href="/history">{t('view_all')}</a>
      </div>
      {#if session.historySupported}<QueryStatus
          pending={recent.isPending}
          error={recent.error}
          empty={!recent.data?.events.length}
          retry={() => recent.refetch()}
        />{#if recent.data?.events.length}<RecentHistory
            events={recent.data.events}
          />{/if}{:else}<div class="status">
          {t('change_history_is_unavailable_on_this_server')}
        </div>{/if}
    </section>
  </div>
  <div style="margin-top: 1rem"><Freshness at={stats.dataUpdatedAt} /></div>{/if}
