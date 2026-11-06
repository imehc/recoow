<script lang="ts">
  import { t } from '#lib/preferences.svelte.js';
  let { from = $bindable(''), to = $bindable('') } = $props();
</script>

<label>{t('start_date')}<input type="date" bind:value={from} max={to || undefined} /></label>
<label>{t('end_date_inclusive')}<input type="date" bind:value={to} min={from || undefined} /></label
>
