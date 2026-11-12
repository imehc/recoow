<script lang="ts">
  import { createQuery } from '@tanstack/svelte-query';
  import { api } from '#lib/api.js';
  import { t } from '#lib/preferences.svelte.js';
  import { source, time } from '#lib/format.js';
  import Freshness from '#lib/components/Freshness.svelte';
  import QueryStatus from '#lib/components/QueryStatus.svelte';
  import PageHeader from '#lib/components/PageHeader.svelte';
  import { Smartphone, Monitor } from '@lucide/svelte';
  const query = createQuery(() => ({
    queryKey: ['devices'],
    queryFn: ({ signal }) => api.devices(signal),
  }));
</script>

<PageHeader
  title={t('devices')}
  description={t('device_count', { count: query.data?.devices.length ?? 0 })}
/>
<QueryStatus
  pending={query.isPending}
  error={query.error}
  empty={!query.data?.devices.length}
  retry={() => query.refetch()}
/>
{#if query.data}
  <div class="device-grid">
    {#each query.data.devices as device (device.id)}
      <section class="panel">
        <div class="panel-heading">
          <div class="device-heading">
            {#if device.profile?.client === 'web'}
              <Monitor size={22} />
            {:else}
              <Smartphone size={22} />
            {/if}
            <div>
              <h2>{device.name || t('unnamed_device')}</h2>
              <p>{source(device.profile)}</p>
            </div>
          </div>
          {#if device.isOwner}
            <span class="operation-badge">{t('owner_device')}</span>
          {/if}
        </div>
        <div class="panel-body">
          <dl class="field-list">
            {#each [[t('app_version'), device.profile?.appVersion ?? t('not_recorded')], [t('os_version'), device.profile?.osVersion ?? t('not_recorded')], [t('last_seen'), time(new Date(device.lastSeenAt).getTime())]] as [label, value] (label)}
              <div class="field">
                <dt>{label}</dt>
                <dd>{value}</dd>
              </div>
            {/each}
          </dl>
        </div>
        <div class="device-id">ID: {device.id}</div>
      </section>
    {/each}
  </div>
  <div class="page-freshness">
    <Freshness at={query.dataUpdatedAt} />
  </div>
{/if}
