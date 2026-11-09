<script lang="ts">
  import { appPath } from '#lib/paths.js';
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
  import PageHeader from '#lib/components/PageHeader.svelte';
  import { Collapsible } from 'bits-ui';
  import { ChevronDown } from '@lucide/svelte';
  const query = createQuery(() => ({
    queryKey: ['event', page.params.eventId],
    queryFn: ({ signal }) => api.event(page.params.eventId ?? '', signal),
    enabled: session.historySupported,
  }));
  const event = $derived(query.data?.event);
</script>

<PageHeader
  title={event?.title || t('change_details')}
  description={event ? entityTitle(event.table) + ' · ' + operationText(event.operation) : ''}
  back="/history"
/>
{#if !session.historySupported}<div class="notice">
    {t('change_history_is_unavailable_on_this_server')}
  </div>{:else}<QueryStatus
    pending={query.isPending}
    error={query.error}
    retry={() => query.refetch()}
  />{/if}
{#if event && query.data}<div class="detail-grid">
    <div class="stack">
      <section class="panel">
        <div class="panel-heading">
          <h2>{t('field_changes')}</h2>
          <a
            class="text-link"
            href={appPath('/records/' + event.table + '/' + encodeURIComponent(event.id))}
            >{t('view_current_record')}</a
          >
        </div>
        <div class="panel-body">
          {#each query.data.changes as change (change.field)}<section class="diff-section">
              <h3>{fieldLabel(event.table, change.field)}</h3>
              <div class="diff">
                <div>
                  <p class="diff-label">{t('before')}</p>
                  <pre class="diff-value before">{valueText(
                      event.table,
                      change.field,
                      change.beforeValue,
                      change.beforePresent,
                      query.data.before?.snapshot,
                    )}</pre>
                </div>
                <div>
                  <p class="diff-label">{t('after')}</p>
                  <pre class="diff-value after">{valueText(
                      event.table,
                      change.field,
                      change.afterValue,
                      change.afterPresent,
                      query.data.after?.snapshot,
                    )}</pre>
                </div>
              </div>
            </section>{/each}
          {#if !query.data.changes.length}<p class="muted">{t('no_field_content_changed')}</p>{/if}
        </div>
      </section>
      <Collapsible.Root class="panel">
        <Collapsible.Trigger class="section-disclosure"
          >{t('view_full_historical_snapshots')}<ChevronDown size={16} /></Collapsible.Trigger
        >
        <Collapsible.Content
          ><div class="historical-snapshots diff">
            <div>
              <h3>{t('before')}</h3>
              <Fields
                includeMetadata
                table={event.table}
                snapshot={query.data.before?.snapshot}
              /><Media embedded snapshot={query.data.before?.snapshot} />
            </div>
            <div>
              <h3>{t('after')}</h3>
              <Fields
                includeMetadata
                table={event.table}
                snapshot={query.data.after.snapshot}
              /><Media embedded snapshot={query.data.after.snapshot} />
            </div>
          </div></Collapsible.Content
        >
      </Collapsible.Root>
      {#if query.data.related.length}<section class="panel">
          <div class="panel-heading"><h2>{t('related_changes')}</h2></div>
          <HistoryList events={query.data.related} />
        </section>{/if}
    </div>
    <aside class="stack">
      <section class="panel">
        <div class="panel-heading"><h2>{t('time_and_sources')}</h2></div>
        <div class="panel-body">
          <dl class="field-list time-summary">
            <div class="field">
              <dt>{t('client_modified_at')}</dt>
              <dd>{time(event.modifiedAtMs)}</dd>
            </div>
            <div class="field">
              <dt>{t('server_accepted_at')}</dt>
              <dd>{time(event.acceptedAtMs)}</dd>
            </div>
          </dl>
        </div>
      </section>
      <section class="panel">
        <div class="panel-heading"><h2>{t('source_summary')}</h2></div>
        <div class="panel-body">
          <dl class="field-list">
            <div class="field">
              <dt>{t('original_edit_source')}</dt>
              <dd>{source(event.originalSource)}</dd>
            </div>
            <div class="field">
              <dt>{t('submission_source')}</dt>
              <dd>{source(event.submission)}</dd>
            </div>
            <div class="field">
              <dt>{t('processing')}</dt>
              <dd>{methodText(event.method)}</dd>
            </div>
          </dl>
        </div>
      </section>
      <Collapsible.Root class="panel"
        ><Collapsible.Trigger class="section-disclosure"
          >{t('view_source_details')}<ChevronDown size={16} /></Collapsible.Trigger
        ><Collapsible.Content
          ><div class="panel-body">
            <dl class="field-list">
              {#each [[t('display_timezone'), timezone()], [t('original_device_id'), event.originalSource?.deviceId ?? t('not_recorded')], [t('original_source_versions'), [event.originalSource?.appVersion, event.originalSource?.osVersion]
                    .filter(Boolean)
                    .join(' · ') || t('not_recorded')], [t('submitting_device_id'), event.submission?.deviceId ?? t('not_recorded')], [t('submission_versions'), [event.submission?.appVersion, event.submission?.osVersion]
                    .filter(Boolean)
                    .join(' · ') || t('not_recorded')], [t('cloud_version'), String(event.version)], [t('event_id'), event.eventId]] as [label, value] (label)}<div
                  class="field"
                >
                  <dt>{label}</dt>
                  <dd>{value}</dd>
                </div>{/each}
            </dl>
            <p class="source-note">{t('source_time_note')}</p>
          </div></Collapsible.Content
        ></Collapsible.Root
      >
      <Freshness at={query.dataUpdatedAt} />
    </aside>
  </div>{/if}
