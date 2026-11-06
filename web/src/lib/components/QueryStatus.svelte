<script lang="ts">
  import { Button } from '#lib/components/ui/button/index.js';
  import { APIError } from '#lib/api.js';
  import { t } from '#lib/preferences.svelte.js';
  import { Inbox, CircleAlert } from '@lucide/svelte';
  let {
    pending = false,
    error = null,
    empty = false,
    retry,
  }: { pending?: boolean; error?: Error | null; empty?: boolean; retry?: () => unknown } = $props();
</script>

{#if pending}<div class="status" role="status">
    <span class="sr-only">{t('loading_cloud_data')}</span>
    <div class="skeleton" aria-hidden="true"><span></span><span></span><span></span></div>
  </div>
{:else if error}<div class="status" role="alert">
    <CircleAlert size={28} />
    <p>
      {error instanceof APIError ? error.message : t('unable_to_load_data_try_again')}
    </p>
    {#if error instanceof APIError && error.requestId}<small
        >{t('request_id')}: {error.requestId}</small
      >{/if}{#if retry}<Button variant="outline" onclick={retry}>{t('retry')}</Button>{/if}
  </div>
{:else if empty}<div class="status">
    <Inbox size={28} />
    <p>{t('no_cloud_data_matches_these_filters')}</p>
    <small>{t('empty_help')}</small>
  </div>{/if}
