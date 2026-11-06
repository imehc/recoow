<script lang="ts">
  import { entity, fieldLabel, valueText } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
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
              href={'/records/media_assets/' + encodeURIComponent(value)}
              >{value} · {t('查看媒体', 'View media')}</a
            >{:else}{valueText(table, key, value)}{/if}
        </dd>
      </div>{/each}
  </dl>{:else}<p class="muted">{t('没有记录快照', 'No record snapshot')}</p>{/if}
