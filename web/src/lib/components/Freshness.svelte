<script lang="ts">
  import { time, timezone } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  let { at }: { at: number } = $props();
</script>

<p class="freshness">
  {t('fetched_at')}
  {time(at)} · {timezone()}<br />{t('includes_synced_cloud_data_only')}
</p>
