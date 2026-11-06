<script lang="ts">
  import { createQuery } from '@tanstack/svelte-query';
  import { api } from '#lib/api.js';
  import { t } from '#lib/preferences.svelte.js';
  import { source, time } from '#lib/format.js';
  import Freshness from '#lib/components/Freshness.svelte';
  import QueryStatus from '#lib/components/QueryStatus.svelte';
  const query = createQuery(() => ({
    queryKey: ['devices'],
    queryFn: ({ signal }) => api.devices(signal),
  }));
</script>

<div class="page-heading">
  <div>
    <p class="eyebrow">{t('来源与设备', 'SOURCES AND DEVICES')}</p>
    <h1>{t('设备', 'Devices')}</h1>
    <p>
      {t(
        '查看账号当前登记的设备；历史保留事件当时的来源资料。',
        'View devices currently registered to your account. History retains source information from the time of each change.',
      )}
    </p>
  </div>
</div>
<QueryStatus
  pending={query.isPending}
  error={query.error}
  empty={!query.data?.devices.length}
  retry={() => query.refetch()}
/>
{#if query.data}<div class="device-grid">
    {#each query.data.devices as device (device.id)}<section class="panel">
        <div class="panel-heading"><h2>{device.name || t('未命名设备', 'Unnamed device')}</h2></div>
        <div class="panel-body">
          <dl class="field-list">
            {#each [[t('来源', 'Source'), source(device.profile)], [t('App 版本', 'App version'), device.profile?.appVersion ?? t('未记录', 'Not recorded')], [t('系统版本', 'OS version'), device.profile?.osVersion ?? t('未记录', 'Not recorded')], [t('最近在线', 'Last seen'), time(new Date(device.lastSeenAt).getTime())], [t('主设备', 'Owner device'), device.isOwner ? t('是', 'Yes') : t('否', 'No')], [t('设备编号', 'Device ID'), device.id]] as [label, value] (label)}<div
                class="field"
              >
                <dt>{label}</dt>
                <dd>{value}</dd>
              </div>{/each}
          </dl>
        </div>
      </section>{/each}
  </div>
  <div style="margin-top: 1rem"><Freshness at={query.dataUpdatedAt} /></div>{/if}
