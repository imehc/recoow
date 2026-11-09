<script lang="ts">
  import { appPath } from '#lib/paths.js';
  import { entity, fieldLabel, valueText } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  import ExpandableText from './ExpandableText.svelte';
  let {
    table,
    snapshot,
    includeMetadata = false,
  }: {
    table: string;
    snapshot?: Record<string, unknown> | null;
    includeMetadata?: boolean;
  } = $props();
  const fields = $derived(
    Object.entries(snapshot ?? {}).filter(([key]) => {
      const definition = entity(table)?.fields.find((field) => field.name === key);
      return (
        includeMetadata ||
        (key !== '_media' && (!definition || !('system' in definition) || !definition.system))
      );
    }),
  );
</script>

{#if snapshot}<dl class="field-list">
    {#each fields as [key, value] (key)}<div class="field">
        <dt>{fieldLabel(table, key)}</dt>
        <dd>
          {#if typeof value === 'string' && value && ['image_asset_id', 'asset_id'].includes(key)}<a
              class="text-link"
              href={appPath('/records/media_assets/' + encodeURIComponent(value))}
              >{value} · {t('view_media')}</a
            >{:else if typeof value === 'string' && (value.length > 500 || value.split('\n').length > 12)}<ExpandableText
              text={valueText(table, key, value, true, snapshot)}
            />{:else}{valueText(table, key, value, true, snapshot)}{/if}
        </dd>
      </div>{/each}
  </dl>{:else}<p class="muted">{t('no_record_snapshot')}</p>{/if}
