<script lang="ts">
  import { t } from '#lib/preferences.svelte.js';
  import DateField from './DateField.svelte';
  let { from = $bindable(''), to = $bindable('') } = $props();
</script>

<DateField label={t('start_date')} bind:value={from} max={to || undefined} />
<DateField
  label={t('end_date_inclusive')}
  bind:value={to}
  min={from || undefined}
  hint={t('end_date_hint')}
/>
