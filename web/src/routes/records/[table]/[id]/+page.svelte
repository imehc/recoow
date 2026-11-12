<script lang="ts">
  import { appPath } from '#lib/paths.js';
  import { page } from '$app/state';
  import { createQuery } from '@tanstack/svelte-query';
  import { api } from '#lib/api.js';
  import { session } from '#lib/session.svelte.js';
  import WebAccessHint from '#lib/components/WebAccessHint.svelte';
  import { entity, entityTitle, title, time } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  import QueryStatus from '#lib/components/QueryStatus.svelte';
  import Freshness from '#lib/components/Freshness.svelte';
  import Fields from '#lib/components/Fields.svelte';
  import Media from '#lib/components/Media.svelte';
  import PageHeader from '#lib/components/PageHeader.svelte';
  import { Button } from '#lib/components/ui/button/index.js';
  import { Network, Pencil } from '@lucide/svelte';
  const table = $derived(page.params.table ?? '');
  const id = $derived(page.params.id ?? '');
  const query = createQuery(() => ({
    queryKey: ['record', table, id],
    queryFn: ({ signal }) => api.record(table, id, signal),
    enabled: !!entity(table),
  }));
</script>

<PageHeader
  title={title(query.data?.snapshot, t('record_details'))}
  description={entityTitle(table)}
  back={'/records/' + table}
>
  {#snippet actions()}
    {#if query.data && !query.data.deletedAtMs && session.canWrite(table)}
      <Button href={appPath('/records/' + table + '/' + encodeURIComponent(id) + '/edit')}>
        <Pencil size={16} />
        {t('edit_record')}
      </Button>
    {/if}
    {#if query.data}
      <Button
        variant="outline"
        href={appPath('/records/' + table + '/' + encodeURIComponent(id) + '/relations')}
      >
        <Network size={16} />
        {t('record_relations')}
      </Button>
    {/if}
  {/snippet}
</PageHeader>
<WebAccessHint {table} />
<QueryStatus
  pending={query.isPending && !!entity(table)}
  error={query.error}
  retry={() => query.refetch()}
/>
{#if !entity(table)}
  <div class="status">{t('unknown_record_type')}</div>
{/if}
{#if query.data}
  <div class="detail-grid">
    <div class="stack">
      <section class="panel">
        <div class="panel-heading">
          <h2>{t('record_content')}</h2>
        </div>
        <div class="panel-body">
          <Fields {table} snapshot={query.data.snapshot} />
        </div>
      </section>
      <Media snapshot={query.data.snapshot} />
    </div>
    <aside class="stack">
      <section class="panel">
        <div class="panel-heading">
          <h2>{t('cloud_information')}</h2>
        </div>
        <div class="panel-body">
          <dl class="field-list">
            <div class="field">
              <dt>{t('cloud_version')}</dt>
              <dd>{String(query.data.version)}</dd>
            </div>
            <div class="field">
              <dt>{t('client_modified_at')}</dt>
              <dd>{time(query.data.updatedAtMs)}</dd>
            </div>
            <div class="field">
              <dt>{t('status')}</dt>
              <dd>
                {query.data.deletedAtMs ? t('deleted_dbdda0') : t('current_record')}
              </dd>
            </div>
          </dl>
          {#if session.historySupported}
            <a
              class="text-link"
              href={appPath(
                '/history?table=' + encodeURIComponent(table) + '&id=' + encodeURIComponent(id),
              )}
            >
              {t('view_all_changes_to_this_record')}
            </a>
          {/if}
        </div>
      </section>
      <p class="record-id">ID: {id}</p>
      <Freshness at={query.dataUpdatedAt} />
    </aside>
  </div>
{/if}
