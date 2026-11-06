<script lang="ts">
  import type { ChangeEvent } from '#lib/generated/api/models/ChangeEvent.js';
  import { entityTitle, operationText, source, time } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  let { events }: { events: ChangeEvent[] } = $props();
</script>

<div class="table-wrap">
  <table>
    <thead
      ><tr
        ><th>{t('record_change')}</th><th>{t('original_source')}</th><th
          >{t('client_modified_at')}</th
        ><th>{t('server_accepted_at')}</th><th>{t('details')}</th></tr
      ></thead
    ><tbody
      >{#each events as event (event.eventId)}<tr
          ><td
            ><a class="record-title" href={'/history/' + encodeURIComponent(event.eventId)}
              >{event.title || event.id}</a
            >
            <div class="record-id">
              {entityTitle(event.table)} · {operationText(event.operation)}
            </div></td
          ><td>{source(event.originalSource)}</td><td>{time(event.modifiedAtMs)}</td><td
            >{time(event.acceptedAtMs)}</td
          ><td
            ><a class="text-link" href={'/history/' + encodeURIComponent(event.eventId)}
              >{t('view_change')}</a
            ></td
          ></tr
        >{/each}</tbody
    >
  </table>
</div>
