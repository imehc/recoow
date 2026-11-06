<script lang="ts">
  import { History } from '@lucide/svelte';
  import type { ChangeEvent } from '#lib/generated/api/models/ChangeEvent.js';
  import { entityTitle, operationText, source, time } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  let { events }: { events: ChangeEvent[] } = $props();
</script>

{#each events as event (event.eventId)}
  <a class="timeline-item" href={'/history/' + encodeURIComponent(event.eventId)}>
    <span class="timeline-icon"><History size={17} /></span>
    <div class="timeline-content">
      <strong class="record-title">{event.title || event.id}</strong>
      <p>
        {entityTitle(event.table)} · {operationText(event.operation)} · {source(
          event.originalSource,
        )}
      </p>
      <p>{t('accepted_by_server_at')} {time(event.acceptedAtMs)}</p>
    </div>
  </a>
{/each}
