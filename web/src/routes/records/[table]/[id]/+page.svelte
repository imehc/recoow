<script lang="ts">
  import { page } from '$app/state';
  import { createQuery } from '@tanstack/svelte-query';
  import { api } from '#lib/api.js';
  import { session } from '#lib/session.svelte.js';
  import { entity, entityTitle, title, time } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  import QueryStatus from '#lib/components/QueryStatus.svelte';
  import Freshness from '#lib/components/Freshness.svelte';
  import Fields from '#lib/components/Fields.svelte';
  import Media from '#lib/components/Media.svelte';
  const table = $derived(page.params.table ?? '');
  const id = $derived(page.params.id ?? '');
  const query = createQuery(() => ({
    queryKey: ['record', table, id],
    queryFn: ({ signal }) => api.record(table, id, signal),
    enabled: !!entity(table),
  }));
</script>

<div class="page-heading">
  <div>
    <p class="eyebrow"><a href={'/records/' + table}>{entityTitle(table)}</a></p>
    <h1>{title(query.data?.snapshot, t('记录详情', 'Record details'))}</h1>
    <p class="record-id">{id}</p>
  </div>
</div>
<QueryStatus
  pending={query.isPending && !!entity(table)}
  error={query.error}
  retry={() => query.refetch()}
/>
{#if !entity(table)}<div class="status">{t('未知的记录类型', 'Unknown record type')}</div>{/if}
{#if query.data}<div class="detail-grid">
    <div class="stack">
      <section class="panel">
        <div class="panel-heading"><h2>{t('记录内容', 'Record content')}</h2></div>
        <div class="panel-body"><Fields {table} snapshot={query.data.snapshot} /></div>
      </section>
      <Media snapshot={query.data.snapshot} />
    </div>
    <aside class="stack">
      <section class="panel">
        <div class="panel-heading"><h2>{t('云端信息', 'Cloud information')}</h2></div>
        <div class="panel-body">
          <dl class="field-list">
            <div class="field">
              <dt>{t('云端版本', 'Cloud version')}</dt>
              <dd>{String(query.data.version)}</dd>
            </div>
            <div class="field">
              <dt>{t('客户端修改时间', 'Client modified at')}</dt>
              <dd>{time(query.data.updatedAtMs)}</dd>
            </div>
            <div class="field">
              <dt>{t('状态', 'Status')}</dt>
              <dd>
                {query.data.deletedAtMs ? t('已删除', 'Deleted') : t('当前记录', 'Current record')}
              </dd>
            </div>
          </dl>
          {#if session.historySupported}<a
              class="text-link"
              href={'/history?table=' + encodeURIComponent(table) + '&id=' + encodeURIComponent(id)}
              >{t('查看这条记录的所有变化 →', 'View all changes to this record →')}</a
            >{/if}
        </div>
      </section>
      <Freshness at={query.dataUpdatedAt} />
    </aside>
  </div>{/if}
