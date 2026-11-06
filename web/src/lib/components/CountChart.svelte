<script lang="ts">
  import { onMount } from 'svelte';
  import { init, use } from 'echarts/core';
  import { BarChart } from 'echarts/charts';
  import { GridComponent, TooltipComponent } from 'echarts/components';
  import { CanvasRenderer } from 'echarts/renderers';
  import type { ECharts } from 'echarts/core';
  import { preferences, t } from '#lib/preferences.svelte.js';
  import { entityTitle } from '#lib/format.js';
  import { Collapsible } from 'bits-ui';
  import { ChevronDown } from '@lucide/svelte';
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
    const color = preferences.dark ? '#a3a3a3' : '#646b76';
    chart?.setOption({
      animation: !matchMedia('(prefers-reduced-motion: reduce)').matches,
      grid: { left: 100, right: 40, top: 20, bottom: 32 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: preferences.dark ? '#292929' : '#fff',
        borderWidth: 0,
        textStyle: { color: preferences.dark ? '#ededed' : '#1f2329' },
      },
      xAxis: {
        type: 'value',
        minInterval: 1,
        axisLabel: { color },
        splitLine: {
          lineStyle: { color: preferences.dark ? '#363636' : '#e5e7eb', type: 'dashed' },
        },
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
          barMaxWidth: 14,
          label: { show: true, position: 'right', color, fontSize: 11 },
          itemStyle: {
            color: preferences.dark ? '#69a9ff' : '#1668dc',
            borderRadius: [0, 3, 3, 0],
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
<Collapsible.Root class="counts-toggle">
  <Collapsible.Trigger>{t('view_all_counts')}<ChevronDown size={14} /></Collapsible.Trigger>
  <Collapsible.Content>
    <dl class="field-list">
      {#each Object.entries(counts) as [table, count] (table)}<div class="field">
          <dt>{entityTitle(table)}</dt>
          <dd>{String(count)}</dd>
        </div>{/each}
    </dl>
  </Collapsible.Content>
</Collapsible.Root>
