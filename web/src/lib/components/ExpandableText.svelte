<script lang="ts">
  import { t } from '#lib/preferences.svelte.js';
  let { text }: { text: string } = $props();
  let expanded = $state(false);
  const preview = $derived(text.split('\n').slice(0, 12).join('\n').slice(0, 500));
  const truncated = $derived(preview.length < text.length);
</script>

<div class="expandable-copy">
  {expanded || !truncated ? text : preview + '…'}
  {#if truncated}
    <button type="button" aria-expanded={expanded} onclick={() => (expanded = !expanded)}>
      {expanded ? t('collapse_content') : t('expand_content')}
    </button>
  {/if}
</div>
