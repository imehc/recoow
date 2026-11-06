<script lang="ts">
  import { Button } from '#lib/components/ui/button/index.js';
  import { APIError } from '#lib/api.js';
  import { t } from '#lib/preferences.svelte.js';
  let {
    pending = false,
    error = null,
    empty = false,
    retry,
  }: { pending?: boolean; error?: Error | null; empty?: boolean; retry?: () => unknown } = $props();
</script>

{#if pending}<div class="status" role="status">{t('loading_cloud_data')}</div>
{:else if error}<div class="status" role="alert">
    <p>
      {error instanceof APIError ? error.message : t('unable_to_load_data_try_again')}
    </p>
    {#if error instanceof APIError && error.requestId}<small
        >{t('request_id')}: {error.requestId}</small
      >{/if}{#if retry}<Button variant="outline" onclick={retry}>{t('retry')}</Button>{/if}
  </div>
{:else if empty}<div class="status">
    {t('no_cloud_data_matches_these_filters')}
  </div>{/if}
