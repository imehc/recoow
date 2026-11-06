<script lang="ts">
  import { t } from '#lib/preferences.svelte.js';
  let { from = $bindable(''), to = $bindable('') } = $props();
</script>

<label
  >{t('开始日期', 'Start date')}<input type="date" bind:value={from} max={to || undefined} /></label
>
<label
  >{t('结束日期（含）', 'End date (inclusive)')}<input
    type="date"
    bind:value={to}
    min={from || undefined}
  /></label
>
