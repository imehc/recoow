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
    <p class="eyebrow">{t('你的云端空间', 'YOUR CLOUD SPACE')}</p>
    <h1>{t('记录概览', 'Record overview')}</h1>
    <p>{t('从云端查看生活的记录和变化。', 'Explore your synced records and how they changed.')}</p>
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
      >{t('应用统计区间', 'Apply date range')}</Button
    >
  </form>
  <div class="panel-body freshness">
    {t(
      '数量按客户端修改时间筛选；金额按账单发生时间统计，退款不计入。空区间表示全部云端数据。',
      'Counts use client modification time. Amounts use the bill date and exclude refunds. Empty dates include all cloud data.',
    )}
  </div>
</section>
<QueryStatus pending={stats.isPending} error={stats.error} retry={() => stats.refetch()} />
{#if stats.data}<div class="metrics">
    {#each [{ label: t('记录总数', 'Total records'), value: String(total), icon: Files }, { label: t('账单支出', 'Bill expenses'), value: money(stats.data.billExpenseCents), icon: ArrowUpRight }, { label: t('账单收入', 'Bill income'), value: money(stats.data.billIncomeCents), icon: ArrowDownLeft }, { label: t('储值消费', 'Stored value used'), value: money(stats.data.billStoredValueUseCents), icon: Wallet }] as metric (metric.label)}<div
        class="panel metric"
      >
        <div class="metric-icon"><metric.icon size={19} /></div>
        <div class="metric-label">{metric.label}</div>
        <div class="metric-value">{metric.value}</div>
        <div class="metric-note">{t('当前统计区间', 'Selected date range')}</div>
      </div>{/each}
  </div>
  <div class="overview-grid">
    <section class="panel">
      <div class="panel-heading"><h2>{t('记录分布', 'Records by type')}</h2></div>
      <CountChart counts={stats.data.recordCounts} />
    </section>
    <section class="panel">
      <div class="panel-heading">
        <h2>{t('最近的云端变化', 'Recent cloud changes')}</h2>
        <a class="text-link" href="/history">{t('查看全部', 'View all')}</a>
      </div>
      {#if session.historySupported}<QueryStatus
          pending={recent.isPending}
          error={recent.error}
          empty={!recent.data?.events.length}
          retry={() => recent.refetch()}
        />{#if recent.data?.events.length}<RecentHistory
            events={recent.data.events}
          />{/if}{:else}<div class="status">
          {t('当前服务端未开放变更历史', 'Change history is unavailable on this server.')}
        </div>{/if}
    </section>
  </div>
  <div style="margin-top: 1rem"><Freshness at={stats.dataUpdatedAt} /></div>{/if}
