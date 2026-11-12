<script lang="ts">
  import { page } from '$app/state';
  import { createQuery } from '@tanstack/svelte-query';
  import { api } from '#lib/api.js';
  import { entity, title } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  import PageHeader from '#lib/components/PageHeader.svelte';
  import QueryStatus from '#lib/components/QueryStatus.svelte';
  import RecordRelations from '#lib/components/RecordRelations.svelte';
  const table = $derived(page.params.table ?? '');
  const id = $derived(page.params.id ?? '');
  const query = createQuery(() => ({
    queryKey: ['record', table, id],
    queryFn: ({ signal }) => api.record(table, id, signal),
    enabled: !!entity(table),
  }));
</script>

<PageHeader
  title={t('record_relations')}
  description={title(query.data?.snapshot, id)}
  back={'/records/' + table + '/' + encodeURIComponent(id)}
/>
<QueryStatus
  pending={query.isPending && !!entity(table)}
  error={query.error}
  retry={() => query.refetch()}
/>
{#if !entity(table)}<div class="status">{t('unknown_record_type')}</div>{/if}
{#if query.data}{#key `${table}:${id}:${query.dataUpdatedAt}`}<RecordRelations
      root={query.data}
    />{/key}{/if}
