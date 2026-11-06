<script lang="ts">
  import { createQuery } from '@tanstack/svelte-query';
  import { Wallet, ArrowDownLeft, ArrowUpRight, Files } from '@lucide/svelte';
  import { api } from '#lib/api.js';
  import { session } from '#lib/session.svelte.js';
  import { t } from '#lib/preferences.svelte.js';
  import { dateRange, integer, money } from '#lib/format.js';
  import { ChevronRight } from '@lucide/svelte';
  import PageHeader from '#lib/components/PageHeader.svelte';
  import { recordGroups, groupTitle } from '#lib/record-groups.js';
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

<PageHeader
  title={t('record_overview')}
  description={t('explore_your_synced_records_and_how_they_changed')}
/>
<section class="panel filter-panel">
  <form
    class="filters overview-filters"
    onsubmit={(event) => {
      event.preventDefault();
      range = dateRange(from, to);
    }}
  >
    <DateRange bind:from bind:to />
    <div class="filter-buttons">
      <Button type="submit">{t('apply_date_range')}</Button><Button
        variant="outline"
        onclick={() => {
          from = '';
          to = '';
          range = {};
        }}>{t('reset_filters')}</Button
      >
    </div>
    <p class="filter-hint overview-hint">
      {t('counts_use_client_modification_dates_amounts_use_bill_dates_and_exclude')}
    </p>
  </form>
</section>
<QueryStatus pending={stats.isPending} error={stats.error} retry={() => stats.refetch()} />
{#if stats.data}<section class="panel summary-strip" aria-label={t('record_overview')}>
    <div class="summary-item">
      <span class="summary-label"><Files size={16} />{t('total_records')}</span><strong
        class="summary-value">{total.toLocaleString()}</strong
      >
    </div>
    <div class="summary-item">
      <span class="summary-label"><ArrowUpRight size={16} />{t('bill_expenses')}</span><strong
        class="summary-value expense">{money(stats.data.billExpenseCents)}</strong
      >
    </div>
    <div class="summary-item">
      <span class="summary-label"><ArrowDownLeft size={16} />{t('bill_income')}</span><strong
        class="summary-value income">{money(stats.data.billIncomeCents)}</strong
      >
    </div>
    <div class="summary-item">
      <span class="summary-label"><Wallet size={16} />{t('stored_value_use')}</span><strong
        class="summary-value">{money(stats.data.billStoredValueUseCents)}</strong
      >
    </div>
  </section>{/if}
<div class="overview-grid">
  <section class="panel">
    <div class="panel-heading"><h2>{t('record_distribution')}</h2></div>
    {#if stats.data && total > 0}<CountChart counts={stats.data.recordCounts} />{:else}<QueryStatus
        pending={stats.isPending}
        error={stats.error}
        empty={!!stats.data}
      />{/if}
  </section>
  <section class="panel">
    <div class="panel-heading">
      <h2>{t('recent_cloud_changes')}</h2>
      <a class="text-link" href="/history">{t('view_all')}</a>
    </div>
    {#if !session.historySupported}<div class="status">
        {t('change_history_is_unavailable_on_this_server')}
      </div>{:else}<QueryStatus
        pending={recent.isPending}
        error={recent.error}
        empty={!recent.data?.events.length}
        retry={() => recent.refetch()}
      />{#if recent.data}<RecentHistory events={recent.data.events} />{/if}{/if}
  </section>
</div>
<section class="panel">
  <div class="panel-heading">
    <h2>{t('record_categories')}</h2>
    <a class="text-link" href="/records">{t('view_all')}</a>
  </div>
  <div class="category-links">
    {#each recordGroups as group (group.id)}<a
        class="category-link"
        href={'/records/' + group.tables[0]}
        ><span>{groupTitle(group)}</span><ChevronRight size={14} /></a
      >{/each}
  </div>
</section>
<div class="page-freshness"><Freshness at={stats.dataUpdatedAt} /></div>
