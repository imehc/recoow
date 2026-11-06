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
    <p class="eyebrow">{t('sources_and_devices')}</p>
    <h1>{t('devices')}</h1>
    <p>
      {t('view_devices_currently_registered_to_your_account_history_retains_source')}
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
        <div class="panel-heading"><h2>{device.name || t('unnamed_device')}</h2></div>
        <div class="panel-body">
          <dl class="field-list">
            {#each [[t('source'), source(device.profile)], [t('app_version'), device.profile?.appVersion ?? t('not_recorded')], [t('os_version'), device.profile?.osVersion ?? t('not_recorded')], [t('last_seen'), time(new Date(device.lastSeenAt).getTime())], [t('owner_device'), device.isOwner ? t('yes') : t('no')], [t('device_id'), device.id]] as [label, value] (label)}<div
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
