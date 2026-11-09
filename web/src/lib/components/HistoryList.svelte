<script lang="ts">
  import { appPath } from '#lib/paths.js';
  import type { ChangeEvent } from '#lib/generated/api/models/ChangeEvent.js';
  import { entityTitle, operationText, source, time } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  import { ArrowRight } from '@lucide/svelte';
  let { events }: { events: ChangeEvent[] } = $props();
</script>

<div class="table-wrap">
  <table class="data-table">
    <thead
      ><tr
        ><th>{t('record_change')}</th><th>{t('original_source')}</th><th
          >{t('client_modified_at')}</th
        ><th>{t('server_accepted_at')}</th><th>{t('details')}</th></tr
      ></thead
    ><tbody
      >{#each events as event (event.eventId)}<tr
          ><td
            ><a class="record-title" href={appPath('/history/' + encodeURIComponent(event.eventId))}
              >{event.title || event.id}</a
            >
            <div class="record-id">
              {entityTitle(event.table)}
              <span class={'operation-badge ' + event.operation}
                >{operationText(event.operation)}</span
              >
            </div></td
          ><td data-label={t('original_source')}>{source(event.originalSource)}</td><td
            data-label={t('client_modified_at')}
            class="time-cell">{time(event.modifiedAtMs)}</td
          ><td data-label={t('server_accepted_at')} class="time-cell">{time(event.acceptedAtMs)}</td
          ><td class="row-action"
            ><a class="text-link" href={appPath('/history/' + encodeURIComponent(event.eventId))}
              >{t('view_change')}<ArrowRight size={14} /></a
            ></td
          ></tr
        >{/each}</tbody
    >
  </table>
</div>
