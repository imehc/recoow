<script lang="ts">
  import { page } from '$app/state';
  import { session } from '#lib/session.svelte.js';
  import { entityTitle } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  import { editableTables } from '#lib/record-editor.js';
  import PageHeader from '#lib/components/PageHeader.svelte';
  import RecordEditor from '#lib/components/RecordEditor.svelte';
  const table = $derived(page.params.table ?? '');
</script>

<PageHeader title={t('new_record')} description={entityTitle(table)} back={'/records/' + table} />
{#if editableTables.includes(table) && session.canWrite(table)}{#key table}<RecordEditor
      {table}
    />{/key}{:else}<div class="status" role="alert">
    {t('this_session_cannot_perform_this_action')}
  </div>{/if}
