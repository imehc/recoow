<script lang="ts">
  import { time, timezone } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  let { at }: { at: number } = $props();
</script>

{#if at > 0}
  <p class="freshness" title={t('includes_synced_cloud_data_only')}>
    {t('fetched_at')}
    {time(at)} · {timezone()}
  </p>
{/if}
