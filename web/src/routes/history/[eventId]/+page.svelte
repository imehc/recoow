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
    <p class="eyebrow"><a href="/history">{t('变更历史', 'CHANGE HISTORY')}</a></p>
    <h1>{event?.title || t('变更详情', 'Change details')}</h1>
    <p>{event ? entityTitle(event.table) + ' · ' + operationText(event.operation) : ''}</p>
  </div>
</div>
{#if !session.historySupported}<div class="notice">
    {t('当前服务端未开放变更历史', 'Change history is unavailable on this server.')}
  </div>{:else}<QueryStatus
    pending={query.isPending}
    error={query.error}
    retry={() => query.refetch()}
  />{/if}
{#if event && query.data}<div class="stack">
    <section class="panel">
      <div class="panel-heading"><h2>{t('时间与来源', 'Time and sources')}</h2></div>
      <div class="panel-body">
        <dl class="field-list">
          {#each [[t('客户端修改时间', 'Client modified at'), time(event.modifiedAtMs)], [t('服务端接受时间', 'Server accepted at'), time(event.acceptedAtMs)], [t('显示时区', 'Display timezone'), timezone()], [t('原始修改来源', 'Original edit source'), source(event.originalSource)], [t('原始设备标识', 'Original device ID'), event.originalSource?.deviceId ?? t('未记录', 'Not recorded')], [t('原始来源版本', 'Original source versions'), [event.originalSource?.appVersion, event.originalSource?.osVersion]
                .filter(Boolean)
                .join(' · ') || t('未记录', 'Not recorded')], [t('本次提交来源', 'Submission source'), source(event.submission)], [t('提交设备标识', 'Submitting device ID'), event.submission?.deviceId ?? t('未记录', 'Not recorded')], [t('提交来源版本', 'Submission versions'), [event.submission?.appVersion, event.submission?.osVersion]
                .filter(Boolean)
                .join(' · ') || t('未记录', 'Not recorded')], [t('处理方式', 'Processing'), methodText(event.method)], [t('云端版本', 'Cloud version'), String(event.version)], [t('事件编号', 'Event ID'), event.eventId]] as [label, value] (label)}<div
              class="field"
            >
              <dt>{label}</dt>
              <dd>{value}</dd>
            </div>{/each}
        </dl>
        <p class="freshness">
          {t(
            '修改时间来自客户端，接受时间表示服务端收到并接受了这次变更。离线多次修改若合并上传，只展示云端最终收到的变化；旧历史缺少的信息不推算。来源由客户端声明，提交身份由服务端鉴权确定。',
            'Modification time is supplied by the client; acceptance time is when the server accepted the change. Offline edits may be combined before upload. Missing historical information is not inferred. Source metadata is client-declared; submission identity is authenticated by the server.',
          )}
        </p>
      </div>
    </section>
    <section class="panel">
      <div class="panel-heading">
        <h2>{t('字段变化', 'Field changes')}</h2>
        <a class="text-link" href={'/records/' + event.table + '/' + encodeURIComponent(event.id)}
          >{t('查看当前记录', 'View current record')}</a
        >
      </div>
      <div class="panel-body stack">
        {#each query.data.changes as change (change.field)}<section>
            <h3 style="font-size: 0.88rem; margin-bottom: 0.7rem">
              {fieldLabel(event.table, change.field)}
            </h3>
            <div class="diff">
              <div>
                <p class="freshness">{t('修改前', 'Before')}</p>
                <pre class="diff-value before">{valueText(
                    event.table,
                    change.field,
                    change.beforeValue,
                    change.beforePresent,
                  )}</pre>
              </div>
              <div>
                <p class="freshness">{t('修改后', 'After')}</p>
                <pre class="diff-value after">{valueText(
                    event.table,
                    change.field,
                    change.afterValue,
                    change.afterPresent,
                  )}</pre>
              </div>
            </div>
          </section>{/each}{#if !query.data.changes.length}<p class="muted">
            {t('本次没有字段内容变化', 'No field content changed.')}
          </p>{/if}
      </div>
    </section>
    <section class="panel">
      <div class="panel-body">
        <details>
          <summary>{t('查看完整历史快照', 'View full historical snapshots')}</summary>
          <div class="diff">
            <div>
              <h2>{t('修改前', 'Before')}</h2>
              <Fields
                includeMetadata
                table={event.table}
                snapshot={query.data.before?.snapshot}
              /><Media snapshot={query.data.before?.snapshot} />
            </div>
            <div>
              <h2>{t('修改后', 'After')}</h2>
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
        <div class="panel-heading"><h2>{t('关联变化', 'Related changes')}</h2></div>
        <HistoryList events={query.data.related} />
      </section>{/if}<Freshness at={query.dataUpdatedAt} />
  </div>{/if}
