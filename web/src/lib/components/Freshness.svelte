<script lang="ts">
  import { time, timezone } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  let { at }: { at: number } = $props();
</script>

<p class="freshness">
  {t('数据获取于', 'Fetched at')}
  {time(at)} · {timezone()}<br />{t('仅含已同步到云端的数据', 'Includes synced cloud data only')}
</p>
