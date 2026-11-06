<script lang="ts">
  import * as Dialog from '#lib/components/ui/dialog/index.js';
  import { Button } from '#lib/components/ui/button/index.js';
  import { t } from '#lib/preferences.svelte.js';
  import { mediaReferences, mediaURL } from '#lib/media.js';
  let { snapshot }: { snapshot?: Record<string, unknown> | null } = $props();
  let selected = $state('');
  let failed = $state<string[]>([]);
  const references = $derived(mediaReferences(snapshot));
</script>

{#if references.length}<section class="panel">
    <div class="panel-heading"><h2>{t('媒体', 'Media')}</h2></div>
    <div class="panel-body media-grid">
      {#each references as ref (ref.key)}<div class="stack">
          {#if failed.includes(ref.key)}<p class="muted">
              {t(
                '媒体不可用，可能未上传或已删除',
                'Media is unavailable. It may not have been uploaded or may have been deleted.',
              )}
            </p>
          {:else if /^image\/(jpeg|png|gif|webp|avif)$/.test(ref.contentType ?? '')}<button
              type="button"
              class="media-button"
              aria-label={t('放大图片', 'Enlarge image')}
              onclick={() => (selected = ref.key)}
              ><img
                src={mediaURL(ref.key)}
                alt={t('记录附件', 'Record attachment')}
                loading="lazy"
                onerror={() => (failed = [...failed, ref.key])}
              /></button
            >
          {:else}<p class="muted">{ref.contentType || t('附件', 'Attachment')}</p>{/if}
          <a class="text-link" href={mediaURL(ref.key)} target="_blank" rel="noopener noreferrer"
            >{t('打开原文件', 'Open original file')}</a
          >
        </div>{/each}
    </div>
  </section>{/if}
<Dialog.Root
  open={!!selected}
  onOpenChange={(open) => {
    if (!open) selected = '';
  }}
  ><Dialog.Content class="sm:max-w-3xl" showCloseButton={false}
    ><Dialog.Header
      ><Dialog.Title>{t('图片预览', 'Image preview')}</Dialog.Title><Dialog.Description
        >{t('云端保存的原始附件', 'Original attachment saved in the cloud')}</Dialog.Description
      ></Dialog.Header
    >{#if selected}<img
        class="preview-image"
        src={mediaURL(selected)}
        alt={t('放大后的记录附件', 'Enlarged record attachment')}
      />{/if}<Button variant="outline" onclick={() => (selected = '')}>{t('关闭', 'Close')}</Button
    ></Dialog.Content
  ></Dialog.Root
>
