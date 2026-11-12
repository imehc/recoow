<script lang="ts">
  import { onMount } from 'svelte';
  import { init, use } from 'echarts/core';
  import { GraphChart } from 'echarts/charts';
  import { CanvasRenderer } from 'echarts/renderers';
  import type { ECharts } from 'echarts/core';
  import { preferences, t } from '#lib/preferences.svelte.js';
  import { title, entityTitle } from '#lib/format.js';
  import { recordKey, type RelationNode, type RelationEdge } from '#lib/record-relations.js';
  use([GraphChart, CanvasRenderer]);
  let {
    nodes,
    edges,
    rootKey,
    selected,
    reset = 0,
    onselect,
  }: {
    nodes: RelationNode[];
    edges: RelationEdge[];
    rootKey: string;
    selected: string;
    reset?: number;
    onselect: (key: string) => void;
  } = $props();
  let element: HTMLDivElement;
  let chart = $state<ECharts | null>(null);
  onMount(() => {
    chart = init(element);
    chart.on('click', (event) => {
      if (event.dataType === 'node') onselect((event.data as { id: string }).id);
    });
    const resize = new ResizeObserver(() => chart?.resize());
    resize.observe(element);
    return () => {
      resize.disconnect();
      chart?.dispose();
    };
  });
  $effect(() => {
    // 图表跟随共享主题变量，避免另建一套浅深色配色。
    void preferences.dark;
    void preferences.locale;
    if (!chart) return;
    const styles = getComputedStyle(element);
    const color = (name: string) => styles.getPropertyValue('--' + name).trim();
    chart.setOption({
      animation: false,
      series: [
        {
          type: 'graph',
          layout: 'force',
          roam: true,
          draggable: true,
          force: { repulsion: 250, edgeLength: [100, 160], gravity: 0.08, layoutAnimation: false },
          edgeSymbol: ['none', 'arrow'],
          edgeSymbolSize: 7,
          scaleLimit: { min: 0.3, max: 3 },
          label: {
            show: true,
            position: 'bottom',
            color: color('foreground'),
            fontSize: 12,
            lineHeight: 18,
          },
          emphasis: { focus: 'adjacency' },
          data: nodes.map((node) => {
            const key = recordKey(node);
            const active = selected === key;
            const name = title(node.row?.snapshot, node.savedTitle ?? node.id);
            return {
              id: key,
              name,
              symbolSize: key === rootKey ? 44 : 30,
              itemStyle: {
                color: active ? color('primary') : color('card'),
                borderColor:
                  node.status === 'current' ? color('primary') : color('muted-foreground'),
                borderWidth: 2,
                borderType: node.status === 'current' ? 'solid' : 'dashed',
              },
              label: {
                formatter: `${name.length > 16 ? name.slice(0, 16) + '…' : name}\n${entityTitle(node.table)}`,
              },
            };
          }),
          links: edges.map((edge) => ({
            source: recordKey(edge.source),
            target: recordKey(edge.target),
            lineStyle: {
              color: color('muted-foreground'),
              width: 1.5,
              opacity: 1,
              type: edge.kind === 'current' ? 'solid' : 'dashed',
            },
          })),
        },
      ],
    });
  });
  $effect(() => {
    void reset;
    chart?.setOption({ series: [{ center: ['50%', '50%'], zoom: 1 }] });
  });
</script>

<div
  class="relation-chart"
  bind:this={element}
  role="img"
  aria-label={t('relation_chart_accessibility')}
></div>

<style>
  .relation-chart {
    width: 100%;
    height: 520px;
  }
  @media (max-width: 900px) {
    .relation-chart {
      height: 400px;
    }
  }
  @media (max-width: 600px) {
    .relation-chart {
      height: 340px;
    }
  }
</style>
