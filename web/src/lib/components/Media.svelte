<script lang="ts">
  import * as Dialog from '#lib/components/ui/dialog/index.js';
  import { Button } from '#lib/components/ui/button/index.js';
  import { t } from '#lib/preferences.svelte.js';
  import { mediaReferences, mediaURL } from '#lib/media.js';
  let {
    snapshot,
    embedded = false,
  }: { snapshot?: Record<string, unknown> | null; embedded?: boolean } = $props();
  let selected = $state('');
  let failed = $state<string[]>([]);
  const references = $derived(mediaReferences(snapshot));
</script>

{#if references.length}<section class:panel={!embedded} class:embedded-media={embedded}>
    <div class="panel-heading"><h2>{t('media')}</h2></div>
    <div class="panel-body media-grid">
      {#each references as ref (ref.key)}<div class="stack">
          {#if failed.includes(ref.key)}<p class="muted">
              {t('media_is_unavailable_it_may_not_have_been_uploaded_or_may_have_been_dele')}
            </p>
          {:else if /^image\/(jpeg|png|gif|webp|avif)$/.test(ref.contentType ?? '')}<button
              type="button"
              class="media-button"
              aria-label={t('enlarge_image')}
              onclick={() => (selected = ref.key)}
              ><img
                src={mediaURL(ref.key)}
                alt={t('record_attachment')}
                loading="lazy"
                onerror={() => (failed = [...failed, ref.key])}
              /></button
            >
          {:else}<p class="muted">{ref.contentType || t('attachment')}</p>{/if}
          <a class="text-link" href={mediaURL(ref.key)} target="_blank" rel="noopener noreferrer"
            >{t('open_original_file')}</a
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
      ><Dialog.Title>{t('image_preview')}</Dialog.Title><Dialog.Description
        >{t('original_attachment_saved_in_the_cloud')}</Dialog.Description
      ></Dialog.Header
    >{#if selected}<img
        class="preview-image"
        src={mediaURL(selected)}
        alt={t('enlarged_record_attachment')}
      />{/if}<Button variant="outline" onclick={() => (selected = '')}>{t('close')}</Button
    ></Dialog.Content
  ></Dialog.Root
>
