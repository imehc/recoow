<script lang="ts">
  import { page } from '$app/state';
  import { createQuery } from '@tanstack/svelte-query';
  import { api } from '#lib/api.js';
  import { session } from '#lib/session.svelte.js';
  import { entityTitle } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  import { editableTables } from '#lib/record-editor.js';
  import PageHeader from '#lib/components/PageHeader.svelte';
  import QueryStatus from '#lib/components/QueryStatus.svelte';
  import RecordEditor from '#lib/components/RecordEditor.svelte';
  import type { Row } from '#lib/generated/api/models/Row.js';
  const table = $derived(page.params.table ?? '');
  const id = $derived(page.params.id ?? '');
  const allowed = $derived(editableTables.includes(table) && session.canWrite(table));
  const query = createQuery(() => ({
    queryKey: ['record', table, id],
    queryFn: ({ signal }) => api.record(table, id, signal),
    enabled: allowed,
  }));
  let loaded = $state<{ key: string; row: Row } | null>(null);
  const key = $derived(`${table}:${id}`);
  $effect(() => {
    if (query.data?.id === id && query.data.table === table && loaded?.key !== key)
      loaded = { key, row: query.data };
  });
</script>

<PageHeader
  title={t('edit_record')}
  description={entityTitle(table)}
  back={'/records/' + table + '/' + encodeURIComponent(id)}
/>
{#if !allowed}
  <div class="status" role="alert">
    {t('this_session_cannot_perform_this_action')}
  </div>
{:else}
  <QueryStatus pending={query.isPending} error={query.error} retry={() => query.refetch()} />
  {#if loaded?.key === key}
    {#if loaded.row.deletedAtMs}
      <div class="status">
        {t('this_record_was_not_found_or_was_deleted')}
      </div>
    {:else if loaded.row.snapshot}
      {#key key}
        <RecordEditor {table} row={loaded.row} />
      {/key}
    {/if}
  {/if}
{/if}
