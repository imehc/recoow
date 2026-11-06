<script lang="ts">
  import { onMount } from 'svelte';
  import { init, use } from 'echarts/core';
  import { BarChart } from 'echarts/charts';
  import { GridComponent, TooltipComponent } from 'echarts/components';
  import { CanvasRenderer } from 'echarts/renderers';
  import type { ECharts } from 'echarts/core';
  import { preferences, t } from '#lib/preferences.svelte.js';
  import { entityTitle } from '#lib/format.js';
  use([BarChart, GridComponent, TooltipComponent, CanvasRenderer]);
  let { counts }: { counts: Record<string, number> } = $props();
  let element: HTMLDivElement;
  let chart = $state<ECharts | null>(null);
  const rows = $derived(
    Object.entries(counts)
      .sort((a, b) => Number(b[1]) - Number(a[1]))
      .slice(0, 8)
      .reverse(),
  );
  onMount(() => {
    chart = init(element);
    const resize = new ResizeObserver(() => chart?.resize());
    resize.observe(element);
    return () => {
      resize.disconnect();
      chart?.dispose();
    };
  });
  $effect(() => {
    const color = preferences.dark ? '#9ba8bf' : '#748095';
    chart?.setOption({
      animation: !matchMedia('(prefers-reduced-motion: reduce)').matches,
      grid: { left: 115, right: 35, top: 10, bottom: 25 },
      tooltip: { trigger: 'axis' },
      xAxis: {
        type: 'value',
        minInterval: 1,
        axisLabel: { color },
        splitLine: { lineStyle: { color: preferences.dark ? '#303a4e' : '#e5e9f1' } },
      },
      yAxis: {
        type: 'category',
        data: rows.map(([table]) => entityTitle(table)),
        axisLabel: { color },
        axisTick: { show: false },
        axisLine: { show: false },
      },
      series: [
        {
          type: 'bar',
          data: rows.map(([, count]) => Number(count)),
          barMaxWidth: 18,
          itemStyle: {
            color: preferences.dark ? '#9ba7ff' : '#5365d7',
            borderRadius: [0, 4, 4, 0],
          },
        },
      ],
    });
  });
</script>

<div
  class="chart"
  bind:this={element}
  role="img"
  aria-label={t('cloud_record_counts_by_entity_a_complete_data_list_is_provided_below')}
></div>
<details class="panel-body">
  <summary>{t('view_all_counts')}</summary>
  <dl class="field-list">
    {#each Object.entries(counts) as [table, count] (table)}<div class="field">
        <dt>{entityTitle(table)}</dt>
        <dd>{String(count)}</dd>
      </div>{/each}
  </dl>
</details>
