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
        ><th>{t('记录与操作', 'Record / change')}</th><th>{t('原始修改来源', 'Original source')}</th
        ><th>{t('客户端修改时间', 'Client modified at')}</th><th
          >{t('服务端接受时间', 'Server accepted at')}</th
        ><th>{t('详情', 'Details')}</th></tr
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
              >{t('查看变化', 'View change')}</a
            ></td
          ></tr
        >{/each}</tbody
    >
  </table>
</div>
