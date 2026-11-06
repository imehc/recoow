<script lang="ts">
  import { page } from '$app/state';
  import { createQuery } from '@tanstack/svelte-query';
  import { api } from '#lib/api.js';
  import { session } from '#lib/session.svelte.js';
  import { t } from '#lib/preferences.svelte.js';
  import {
    fieldLabel,
    valueText,
    time,
    source,
    operationText,
    methodText,
    entityTitle,
    timezone,
  } from '#lib/format.js';
  import QueryStatus from '#lib/components/QueryStatus.svelte';
  import Freshness from '#lib/components/Freshness.svelte';
  import Fields from '#lib/components/Fields.svelte';
  import Media from '#lib/components/Media.svelte';
  import HistoryList from '#lib/components/HistoryList.svelte';
  const query = createQuery(() => ({
    queryKey: ['event', page.params.eventId],
    queryFn: ({ signal }) => api.event(page.params.eventId ?? '', signal),
    enabled: session.historySupported,
  }));
  const event = $derived(query.data?.event);
</script>

<div class="page-heading">
  <div>
    <p class="eyebrow"><a href="/history">{t('change_history_868279')}</a></p>
    <h1>{event?.title || t('change_details')}</h1>
    <p>{event ? entityTitle(event.table) + ' · ' + operationText(event.operation) : ''}</p>
  </div>
</div>
{#if !session.historySupported}<div class="notice">
    {t('change_history_is_unavailable_on_this_server')}
  </div>{:else}<QueryStatus
    pending={query.isPending}
    error={query.error}
    retry={() => query.refetch()}
  />{/if}
{#if event && query.data}<div class="stack">
    <section class="panel">
      <div class="panel-heading"><h2>{t('time_and_sources')}</h2></div>
      <div class="panel-body">
        <dl class="field-list">
          {#each [[t('client_modified_at'), time(event.modifiedAtMs)], [t('server_accepted_at'), time(event.acceptedAtMs)], [t('display_timezone'), timezone()], [t('original_edit_source'), source(event.originalSource)], [t('original_device_id'), event.originalSource?.deviceId ?? t('not_recorded')], [t('original_source_versions'), [event.originalSource?.appVersion, event.originalSource?.osVersion]
                .filter(Boolean)
                .join(' · ') || t('not_recorded')], [t('submission_source'), source(event.submission)], [t('submitting_device_id'), event.submission?.deviceId ?? t('not_recorded')], [t('submission_versions'), [event.submission?.appVersion, event.submission?.osVersion]
                .filter(Boolean)
                .join(' · ') || t('not_recorded')], [t('processing'), methodText(event.method)], [t('cloud_version'), String(event.version)], [t('event_id'), event.eventId]] as [label, value] (label)}<div
              class="field"
            >
              <dt>{label}</dt>
              <dd>{value}</dd>
            </div>{/each}
        </dl>
        <p class="freshness">
          {t('modification_time_is_supplied_by_the_client_acceptance_time_is_when_the')}
        </p>
      </div>
    </section>
    <section class="panel">
      <div class="panel-heading">
        <h2>{t('field_changes')}</h2>
        <a class="text-link" href={'/records/' + event.table + '/' + encodeURIComponent(event.id)}
          >{t('view_current_record')}</a
        >
      </div>
      <div class="panel-body stack">
        {#each query.data.changes as change (change.field)}<section>
            <h3 style="font-size: 0.88rem; margin-bottom: 0.7rem">
              {fieldLabel(event.table, change.field)}
            </h3>
            <div class="diff">
              <div>
                <p class="freshness">{t('before')}</p>
                <pre class="diff-value before">{valueText(
                    event.table,
                    change.field,
                    change.beforeValue,
                    change.beforePresent,
                  )}</pre>
              </div>
              <div>
                <p class="freshness">{t('after')}</p>
                <pre class="diff-value after">{valueText(
                    event.table,
                    change.field,
                    change.afterValue,
                    change.afterPresent,
                  )}</pre>
              </div>
            </div>
          </section>{/each}{#if !query.data.changes.length}<p class="muted">
            {t('no_field_content_changed')}
          </p>{/if}
      </div>
    </section>
    <section class="panel">
      <div class="panel-body">
        <details>
          <summary>{t('view_full_historical_snapshots')}</summary>
          <div class="diff">
            <div>
              <h2>{t('before')}</h2>
              <Fields
                includeMetadata
                table={event.table}
                snapshot={query.data.before?.snapshot}
              /><Media snapshot={query.data.before?.snapshot} />
            </div>
            <div>
              <h2>{t('after')}</h2>
              <Fields
                includeMetadata
                table={event.table}
                snapshot={query.data.after.snapshot}
              /><Media snapshot={query.data.after.snapshot} />
            </div>
          </div>
        </details>
      </div>
    </section>
    {#if query.data.related.length}<section class="panel">
        <div class="panel-heading"><h2>{t('related_changes')}</h2></div>
        <HistoryList events={query.data.related} />
      </section>{/if}<Freshness at={query.dataUpdatedAt} />
  </div>{/if}
