<script lang="ts">
  import { onMount } from 'svelte';
  import { SvelteMap } from 'svelte/reactivity';
  import { Network, List, RotateCcw, ArrowRight, Plus, History } from '@lucide/svelte';
  import { Button } from '#lib/components/ui/button/index.js';
  import QueryStatus from './QueryStatus.svelte';
  import RelationChart from './RelationChart.svelte';
  import { appPath } from '#lib/paths.js';
  import { entityTitle, title, time, fieldLabel } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  import { session } from '#lib/session.svelte.js';
  import {
    recordKey,
    edgeKey,
    relationNode,
    relationProgress,
    relationComplete,
    loadRelationPage,
    relationDepths,
    RELATION_NODE_LIMIT,
    RELATION_DEPTH_LIMIT,
    type RelationNode,
    type RelationEdge,
    type RelationProgress,
  } from '#lib/record-relations.js';
  import type { Row } from '#lib/generated/api/models/Row.js';
  let { root }: { root: Row } = $props();
  let nodes = $state<RelationNode[]>([]);
  let edges = $state<RelationEdge[]>([]);
  let progress = $state<Record<string, RelationProgress>>({});
  let selected = $state('');
  let view = $state<'graph' | 'list'>('graph');
  let busy = $state(false);
  let error = $state<Error | null>(null);
  let reset = $state(0);
  const controller = new AbortController();
  const current = $derived(nodes.find((node) => recordKey(node) === selected));
  const depths = $derived(relationDepths(root, edges));
  const complete = $derived(
    !!current && !!progress[selected] && relationComplete(progress[selected]),
  );
  const nodeLimit = $derived(nodes.length >= RELATION_NODE_LIMIT);
  const depthLimit = $derived((depths.get(selected) ?? 0) >= RELATION_DEPTH_LIMIT);
  const canExpand = $derived(
    current?.status === 'current' && !complete && !nodeLimit && !depthLimit,
  );
  const connected = $derived(
    edges.filter(
      (edge) => recordKey(edge.source) === selected || recordKey(edge.target) === selected,
    ),
  );
  const name = (node: RelationNode) => title(node.row?.snapshot, node.savedTitle ?? node.id);
  const status = (node: RelationNode) =>
    node.status === 'current'
      ? t('current_record')
      : node.status === 'deleted'
        ? t('deleted')
        : node.status === 'forbidden'
          ? t('relation_forbidden')
          : t('relation_unavailable');
  const recordPath = (node: RelationNode) =>
    appPath('/records/' + node.table + '/' + encodeURIComponent(node.id));

  onMount(() => {
    nodes = [relationNode(root)];
    selected = recordKey(root);
    void expand();
    return () => controller.abort();
  });

  async function expand() {
    const node = current;
    if (!node?.row || busy || !session.online) return;
    const key = recordKey(node);
    busy = true;
    error = null;
    try {
      const result = await loadRelationPage(
        node.row,
        progress[key] ?? relationProgress(node.row),
        new Set(nodes.map(recordKey)),
        RELATION_NODE_LIMIT - nodes.length,
        controller.signal,
      );
      controller.signal.throwIfAborted();
      const nextNodes = new SvelteMap(nodes.map((node) => [recordKey(node), node]));
      for (const node of result.nodes) nextNodes.set(recordKey(node), node);
      const nextEdges = new SvelteMap(edges.map((edge) => [edgeKey(edge), edge]));
      for (const edge of result.edges) {
        const previous = nextEdges.get(edgeKey(edge));
        nextEdges.set(
          edgeKey(edge),
          previous
            ? { ...previous, fields: [...new Set([...previous.fields, ...edge.fields])] }
            : edge,
        );
      }
      nodes = [...nextNodes.values()];
      edges = [...nextEdges.values()];
      progress = { ...progress, [key]: result.progress };
    } catch (failure) {
      if (!controller.signal.aborted) error = failure as Error;
    } finally {
      busy = false;
    }
  }
</script>

<div class="relations-layout">
  <section class="panel relations-main" aria-label={t('record_relations')}>
    <div class="panel-heading relations-toolbar">
      <div class="view-switch" role="group" aria-label={t('relation_view')}>
        <Button
          variant={view === 'graph' ? 'secondary' : 'ghost'}
          aria-pressed={view === 'graph'}
          onclick={() => (view = 'graph')}><Network size={16} />{t('relation_graph')}</Button
        >
        <Button
          variant={view === 'list' ? 'secondary' : 'ghost'}
          aria-pressed={view === 'list'}
          onclick={() => (view = 'list')}><List size={16} />{t('relation_list')}</Button
        >
      </div>
      {#if view === 'graph'}<Button variant="ghost" onclick={() => reset++}
          ><RotateCcw size={16} />{t('relation_reset_view')}</Button
        >{/if}
    </div>
    {#if view === 'graph'}
      <RelationChart
        {nodes}
        {edges}
        rootKey={recordKey(root)}
        {selected}
        {reset}
        onselect={(key) => {
          selected = key;
          error = null;
        }}
      />
      <div class="relation-legend">
        <span><i class="solid-line"></i>{t('relation_current_link')}</span><span
          ><i class="saved-line"></i>{t('relation_saved_reference')}</span
        >
        <small>{t('relation_chart_hint')}</small>
      </div>
    {:else}
      <ul class="relation-records" aria-label={t('relation_loaded_records')}>
        {#each nodes as node (recordKey(node))}
          <li>
            <button
              class="relation-record"
              class:active={selected === recordKey(node)}
              aria-pressed={selected === recordKey(node)}
              onclick={() => {
                selected = recordKey(node);
                error = null;
              }}
            >
              <span
                ><strong>{name(node)}</strong><small
                  >{entityTitle(node.table)} · {status(node)}{recordKey(node) === recordKey(root)
                    ? ' · ' + t('relation_start_record')
                    : ''}</small
                ></span
              ><ArrowRight size={16} />
            </button>
          </li>
        {/each}
      </ul>
    {/if}
    <div class="table-footer">
      <span class="muted" role="status"
        >{t('relation_loaded_summary', { nodes: nodes.length, edges: edges.length })}</span
      >
    </div>
  </section>

  <aside class="stack relations-sidebar" aria-label={t('relation_selected_record')}>
    {#if current}
      <section class="panel">
        <div class="panel-heading"><h2>{t('relation_selected_record')}</h2></div>
        <div class="panel-body stack">
          <div class="selected-title">
            <h3>{name(current)}</h3>
            <p class="muted">{entityTitle(current.table)} · {status(current)}</p>
          </div>
          <p class="record-id">ID: {current.id}</p>
          {#if current.status === 'current' && current.row}<dl class="field-list">
              <div class="field">
                <dt>{t('cloud_version')}</dt>
                <dd>{String(current.row.version)}</dd>
              </div>
              <div class="field">
                <dt>{t('client_modified_at')}</dt>
                <dd>{time(current.row.updatedAtMs)}</dd>
              </div>
            </dl>
            <Button variant="outline" href={recordPath(current)}
              >{t('view_record')}<ArrowRight size={16} /></Button
            >
          {/if}
          {#if session.historySupported}<Button
              variant="ghost"
              href={appPath(
                '/history?table=' +
                  encodeURIComponent(current.table) +
                  '&id=' +
                  encodeURIComponent(current.id),
              )}><History size={16} />{t('relation_view_history')}</Button
            >{/if}
          <Button disabled={!canExpand || busy || !session.online} onclick={expand}
            ><Plus size={16} />{busy
              ? t('relation_loading')
              : complete
                ? t('relation_expanded')
                : progress[selected]
                  ? t('relation_load_more')
                  : t('relation_expand')}</Button
          >
          {#if nodeLimit}<p class="muted">
              {t('relation_node_limit', { count: RELATION_NODE_LIMIT })}
            </p>
          {:else if depthLimit}<p class="muted">{t('relation_depth_limit')}</p>
          {:else if complete && !connected.length}<p class="muted">{t('relation_empty')}</p>{/if}
          <QueryStatus {error} retry={expand} />
        </div>
      </section>
      {#if connected.length}<section class="panel">
          <div class="panel-heading"><h2>{t('relation_links')}</h2></div>
          <ul class="relation-links">
            {#each connected as edge (edgeKey(edge))}
              {@const other = nodes.find(
                (node) =>
                  recordKey(node) ===
                  (recordKey(edge.source) === selected
                    ? recordKey(edge.target)
                    : recordKey(edge.source)),
              )}
              {#if other}<li>
                  <button
                    class="text-link"
                    onclick={() => {
                      selected = recordKey(other);
                      error = null;
                    }}>{name(other)}<ArrowRight size={14} /></button
                  >
                  <small
                    >{edge.fields.map((field) => fieldLabel(edge.source.table, field)).join('、')} · {edge.kind ===
                    'current'
                      ? t('relation_current_link')
                      : t('relation_saved_reference')}</small
                  >
                  {#if edge.savedTitle}<small>{t('relation_saved_title')}: {edge.savedTitle}</small
                    >{/if}
                </li>{/if}
            {/each}
          </ul>
        </section>{/if}
      <p class="muted relation-note">{t('relation_scope_hint')}</p>
    {/if}
  </aside>
</div>

<style>
  .relations-layout {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 320px;
    gap: 24px;
    align-items: start;
  }
  .relations-main,
  .relations-sidebar {
    min-width: 0;
  }
  .relations-toolbar {
    gap: 12px;
    flex-wrap: wrap;
  }
  .view-switch {
    display: flex;
    gap: 4px;
  }
  .relation-legend {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 12px 20px;
    padding: 12px 20px;
    border-top: 1px solid var(--border);
    font-size: 0.8125rem;
  }
  .relation-legend span {
    display: inline-flex;
    align-items: center;
    gap: 8px;
  }
  .solid-line,
  .saved-line {
    width: 24px;
    border-top: 1.5px solid var(--muted-foreground);
  }
  .saved-line {
    border-top-style: dashed;
  }
  .relation-legend small {
    color: var(--muted-foreground);
  }
  .relation-records,
  .relation-links {
    list-style: none;
    padding: 0;
    margin: 0;
  }
  .relation-records {
    max-height: 560px;
    overflow-y: auto;
  }
  .relation-records li + li {
    border-top: 1px solid var(--border);
  }
  .relation-record {
    display: flex;
    width: 100%;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 16px 20px;
    text-align: left;
    color: var(--foreground);
    background: transparent;
    border: 0;
    cursor: pointer;
  }
  .relation-record:hover {
    background: var(--muted);
  }
  .relation-record.active {
    background: var(--primary-soft);
    color: var(--primary);
  }
  .relation-record > span {
    display: grid;
    gap: 4px;
    min-width: 0;
  }
  .relation-record strong {
    font-size: 0.875rem;
    font-weight: 500;
    overflow-wrap: anywhere;
  }
  .relation-record small {
    font-size: 0.75rem;
    color: var(--muted-foreground);
  }
  .relation-record.active small {
    color: var(--primary);
  }
  .relation-record :global(svg) {
    flex-shrink: 0;
  }
  .selected-title h3 {
    font-size: 1rem;
    margin: 0 0 8px;
    overflow-wrap: anywhere;
  }
  .selected-title p {
    font-size: 0.8125rem;
    margin: 0;
  }
  .relations-sidebar .record-id {
    overflow-wrap: anywhere;
  }
  .relation-links li {
    padding: 12px 20px;
    display: grid;
    gap: 6px;
  }
  .relation-links li + li {
    border-top: 1px solid var(--border);
  }
  .relation-links button {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    width: fit-content;
    text-align: left;
    overflow-wrap: anywhere;
  }
  .relation-links small {
    color: var(--muted-foreground);
    font-size: 0.75rem;
    overflow-wrap: anywhere;
  }
  .relation-note {
    font-size: 0.8125rem;
    margin: 0;
  }
  @media (max-width: 1200px) {
    .relations-layout {
      grid-template-columns: minmax(0, 1fr) 280px;
    }
  }
  @media (max-width: 900px) {
    .relations-layout {
      grid-template-columns: minmax(0, 1fr);
      gap: 16px;
    }
    .relation-records {
      max-height: 400px;
    }
  }
  @media (max-width: 600px) {
    .relation-legend {
      padding: 12px 16px;
    }
    .relation-record {
      padding: 12px 16px;
    }
  }
</style>
