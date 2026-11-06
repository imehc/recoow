<script lang="ts">
  import { Button } from '#lib/components/ui/button/index.js';
  import { APIError } from '#lib/api.js';
  import { t } from '#lib/preferences.svelte.js';
  let {
    pending = false,
    error = null,
    empty = false,
    retry,
  }: { pending?: boolean; error?: Error | null; empty?: boolean; retry?: () => unknown } = $props();
</script>

{#if pending}<div class="status" role="status">{t('正在加载云端数据…', 'Loading cloud data…')}</div>
{:else if error}<div class="status" role="alert">
    <p>
      {error instanceof APIError
        ? error.message
        : t('暂时无法获取数据，请重试', 'Unable to load data. Try again.')}
    </p>
    {#if error instanceof APIError && error.requestId}<small
        >{t('请求编号', 'Request ID')}: {error.requestId}</small
      >{/if}{#if retry}<Button variant="outline" onclick={retry}>{t('重试', 'Retry')}</Button>{/if}
  </div>
{:else if empty}<div class="status">
    {t('暂无符合条件的云端数据', 'No cloud data matches these filters.')}
  </div>{/if}
