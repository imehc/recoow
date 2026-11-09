<script lang="ts">
  import { appPath } from '#lib/paths.js';
  import { History, Plus, Trash2, ChevronRight } from '@lucide/svelte';
  import type { ChangeEvent } from '#lib/generated/api/models/ChangeEvent.js';
  import { entityTitle, operationText, source, time } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  let { events }: { events: ChangeEvent[] } = $props();
</script>

{#each events as event (event.eventId)}
  <a class="timeline-item" href={appPath('/history/' + encodeURIComponent(event.eventId))}>
    <span class="timeline-icon"
      >{#if event.operation === 'insert'}<Plus
          size={15}
        />{:else if event.operation === 'delete'}<Trash2 size={15} />{:else}<History
          size={15}
        />{/if}</span
    >
    <div class="timeline-content">
      <strong class="record-title">{event.title || event.id}</strong>
      <p>
        {entityTitle(event.table)} · {operationText(event.operation)} · {source(
          event.originalSource,
        )}
      </p>
      <p>{t('accepted_by_server_at')} {time(event.acceptedAtMs)}</p>
    </div>
    <ChevronRight size={14} />
  </a>
{/each}
