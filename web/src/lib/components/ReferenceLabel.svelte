<script lang="ts">
  import { createQuery } from '@tanstack/svelte-query';
  import { api } from '#lib/api.js';
  import { title } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  import { appPath } from '#lib/paths.js';
  let { table, id }: { table: string; id: string } = $props();
  const query = createQuery(() => ({
    queryKey: ['record', table, id],
    queryFn: ({ signal }) => api.record(table, id, signal),
    enabled: !!id,
  }));
</script>

<a class="text-link" href={appPath('/records/' + table + '/' + encodeURIComponent(id))}
  >{title(query.data?.snapshot, id)}</a
>
{#if query.isPending}<span class="muted">{t('loading')}</span>{:else if query.error}<span
    class="muted">{t('reference_unavailable')}</span
  >{/if}
