<script lang="ts">
  import type { Snippet } from 'svelte';
  import { ArrowLeft } from '@lucide/svelte';
  import { t } from '#lib/preferences.svelte.js';
  let {
    title,
    description,
    back,
    actions,
  }: { title: string; description?: string; back?: string; actions?: Snippet } = $props();
</script>

<div class="page-heading">
  <div class="page-heading-copy">
    <div class="page-title-row">
      {#if back}<a class="back-button" href={back} aria-label={t('back')} title={t('back')}
          ><ArrowLeft size={18} /></a
        >{/if}
      <h1>{title}</h1>
    </div>
    {#if description}<p>{description}</p>{/if}
  </div>
  {#if actions}<div class="page-heading-actions">{@render actions()}</div>{/if}
</div>
