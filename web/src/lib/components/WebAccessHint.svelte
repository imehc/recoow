<script lang="ts">
  import { session } from '#lib/session.svelte.js';
  import { t } from '#lib/preferences.svelte.js';
  import { editableTables } from '#lib/record-editor.js';
  let { table }: { table: string } = $props();
</script>

{#if editableTables.includes(table) && !session.canWrite(table)}
  <details class="permission-guide">
    <summary>{t('enable_web_editing')}</summary>
    <p>{t('web_editing_owner_steps')}</p>
  </details>
{/if}
