<script lang="ts">
  import { onMount, untrack } from 'svelte';
  import { beforeNavigate, goto } from '$app/navigation';
  import { api, APIError } from '#lib/api.js';
  import { session, queryClient } from '#lib/session.svelte.js';
  import { appPath } from '#lib/paths.js';
  import { fieldLabel, title, timezone, valueText } from '#lib/format.js';
  import { preferences, t } from '#lib/preferences.svelte.js';
  import {
    createDraft,
    draftChanged,
    editedSnapshot,
    editorFields,
    FieldError,
    freezeWrite,
    newSnapshot,
    resolveWrite,
    changedFields,
    type FrozenWrite,
    type WriteMethod,
  } from '#lib/record-editor.js';
  import type { Row } from '#lib/generated/api/models/Row.js';
  import type { PushResult } from '#lib/generated/api/models/PushResult.js';
  import EditorInput from './EditorInput.svelte';
  import Fields from './Fields.svelte';
  import Media from './Media.svelte';
  import { Button } from './ui/button/index.js';
  import * as Dialog from './ui/dialog/index.js';
  import { Save, Trash2, AlertCircle } from '@lucide/svelte';

  let { table, row }: { table: string; row?: Row } = $props();
  // 此编辑器只在首次加载或用户明确重载时重建；查询刷新不能改写输入。
  const baseRow = untrack(() => row);
  const baseTable = untrack(() => table);
  const id = baseRow?.id ?? crypto.randomUUID();
  const generation = untrack(() => session.generation);
  const original = baseRow?.snapshot ?? newSnapshot(baseTable, id, Date.now());
  const initial = createDraft(baseTable, original);
  let draft = $state(createDraft(baseTable, original));
  let candidate = $state<Record<string, unknown>>(original);
  let frozen = $state<FrozenWrite | null>(null);
  let phase = $state<'editing' | 'sending' | 'unknown' | 'conflict' | 'rejected' | 'saved'>(
    'editing',
  );
  let failure = $state('');
  let result = $state<PushResult | null>(null);
  let confirmDelete = $state(false);
  let confirmLeave = $state(false);
  let leaveTo = $state('');
  let leaving = $state(false);
  const fields = $derived(editorFields(table));
  const groups = $derived([...new Set(fields.map((field) => field.group ?? ''))]);
  const dirty = $derived(draftChanged(draft, initial) || !baseRow);
  const protectedInput = $derived(
    !leaving && phase !== 'saved' && (dirty || ['sending', 'unknown', 'conflict'].includes(phase)),
  );
  const locked = $derived(phase !== 'editing' || !session.online || !session.canWrite(table));
  const differences = $derived(
    changedFields(table, original, candidate, result?.current?.snapshot),
  );
  let controller: AbortController | undefined;
  const back = () => appPath('/records/' + table + (row ? '/' + encodeURIComponent(row.id) : ''));
  beforeNavigate((navigation) => {
    if (!protectedInput || !navigation.to) return;
    if (navigation.willUnload) {
      navigation.cancel();
      return;
    }
    navigation.cancel();
    leaveTo = navigation.to.url.href;
    confirmLeave = true;
  });
  onMount(() => {
    const unload = (event: BeforeUnloadEvent) => {
      if (protectedInput) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', unload);
    return () => {
      controller?.abort();
      window.removeEventListener('beforeunload', unload);
    };
  });
  function message(error: unknown): string {
    if (error instanceof FieldError)
      return t(
        error.reason === 'amount_consistency'
          ? 'bill_amount_consistency'
          : error.reason === 'amount'
            ? 'valid_amount_required'
            : error.reason === 'date'
              ? 'valid_date_required'
              : error.reason === 'required'
                ? 'field_required'
                : 'valid_field_required',
        { label: fieldLabel(table, error.field) },
      );
    if (error instanceof TypeError || error instanceof DOMException)
      return t('save_connection_failed');
    return error instanceof Error ? error.message : t('unable_to_save');
  }
  async function submit(method: WriteMethod = baseRow ? 'PUT' : 'POST') {
    if (locked) return;
    failure = '';
    try {
      candidate =
        method === 'DELETE'
          ? original
          : editedSnapshot(baseTable, original, draft, initial, !baseRow);
      frozen = freezeWrite(baseTable, id, generation, baseRow, candidate, method);
      await send(frozen);
    } catch (error) {
      failure = message(error);
    }
  }
  async function send(write: FrozenWrite) {
    if (!session.online || !session.canWrite(table) || phase === 'sending') return;
    phase = 'sending';
    failure = '';
    controller = new AbortController();
    const signal = controller.signal;
    try {
      const receipt = await api.write(write.table, write.id, write.method, write.body, signal);
      signal.throwIfAborted();
      if (
        receipt.id !== id ||
        receipt.table !== table ||
        ![
          'applied',
          'duplicate',
          'conflict',
          'dependency_missing',
          'invalid_revision',
          'mutation_reused',
          'unsupported_table',
          'invalid_operation',
        ].includes(receipt.status)
      )
        throw new Error(t('unable_to_verify_write_receipt'));
      result = receipt;
      if (receipt.status === 'applied' || receipt.status === 'duplicate') {
        phase = 'saved';
        void queryClient.invalidateQueries();
      } else if (receipt.status === 'conflict' && receipt.current) phase = 'conflict';
      else {
        phase = 'rejected';
        failure = t(
          receipt.status === 'dependency_missing'
            ? 'dependency_missing_write'
            : 'revision_rejected',
        );
      }
    } catch (error) {
      if (signal.aborted) return;
      if (error instanceof APIError && error.status >= 400 && error.status < 500) {
        phase = 'rejected';
        failure = message(error);
      } else {
        phase = 'unknown';
        failure = message(error);
      }
    }
  }
  async function resolve(resolution: 'local' | 'remote') {
    if (!frozen || phase !== 'conflict' || !result?.current) return;
    frozen = resolveWrite(frozen, resolution, result.current.version);
    await send(frozen);
  }
  function resume() {
    frozen = null;
    result = null;
    phase = 'editing';
    failure = '';
  }
  const groupLabel = (group: string) =>
    preferences.locale === 'zh'
      ? group || t('record_content')
      : ({
          基本信息: 'Basic information',
          金额: 'Amounts',
          时间: 'Time',
          结算: 'Settlement',
          备注: 'Notes',
          行程: 'Journey',
          关联: 'Related records',
        }[group] ?? t('record_content'));
</script>

{#if phase === 'saved'}
  <section class="panel">
    <div class="status" role="status">
      <h2>{frozen?.method === 'DELETE' ? t('record_deleted') : t('saved')}</h2>
      <p>{result?.applied ? t('revision_saved_in_cloud') : t('cloud_already_has_this_change')}</p>
      <div class="editor-actions">
        <Button href={appPath('/records/' + table + '/' + encodeURIComponent(id))}
          >{t('view_record')}</Button
        >{#if session.historySupported}<Button
            variant="outline"
            href={appPath('/history?table=' + table + '&id=' + encodeURIComponent(id))}
            >{t('view_record_history')}</Button
          >{/if}<Button variant="outline" href={appPath('/records/' + table)}
          >{t('back_to_records')}</Button
        >
      </div>
    </div>
  </section>
{:else}
  <div class="editor-layout">
    <form
      id="record-editor-form"
      class="panel editor-form"
      onsubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      {#each groups as group (group)}<fieldset disabled={locked}>
          <legend>{groupLabel(group)}</legend>
          <div class="editor-fields">
            {#each fields.filter((field) => (field.group ?? '') === group) as field (field.name)}<EditorInput
                {table}
                {field}
                bind:draft={draft[field.name]}
                disabled={locked}
                snapshot={{
                  ...original,
                  transaction_type: draft.transaction_type?.text ?? original.transaction_type,
                }}
              />{/each}
          </div>
        </fieldset>{/each}
      <p class="editor-hint">{t('required_fields_hint')} · {t('timezone', { zone: timezone() })}</p>
      {#if row}<details class="preserved-fields">
          <summary>{t('other_saved_content')}</summary>
          <div class="preserved-content">
            <Fields
              {table}
              snapshot={Object.fromEntries(
                Object.entries(original).filter(
                  ([key]) => !fields.some((field) => field.name === key),
                ),
              )}
            />
          </div>
        </details>{/if}
    </form>
    <aside class="editor-sidebar stack" aria-label={t('save_record')}>
      <section class="panel">
        <div class="panel-body editor-save">
          {#if row}<p class="muted">
              {t('editing_version', { version: String(row.version) })}
            </p>{/if}
          {#if !session.canWrite(table)}<p role="alert">
              {t('this_session_cannot_perform_this_action')}
            </p>{/if}
          {#if !session.online}<p role="status">{t('offline_edits_stay_on_this_page')}</p>{/if}
          {#if phase === 'unknown'}<div role="alert">
              <h2>{t('write_result_unknown')}</h2>
              <p>{t('retry_frozen_write_hint')}</p>
            </div>
            <Button
              disabled={!session.online || !session.canWrite(table)}
              onclick={() => frozen && send(frozen)}>{t('confirm_write_result')}</Button
            >
          {:else if phase === 'sending'}<p role="status">{t('saving')}</p>
            <Button disabled>{t('saving')}</Button>
          {:else if phase === 'conflict'}<p role="alert">{t('record_changed_review_conflict')}</p>
          {:else if phase === 'rejected'}<p role="alert">{t('write_not_applied')}</p>
            <Button variant="outline" onclick={resume}>{t('continue_editing')}</Button>
          {:else}<Button
              type="submit"
              form="record-editor-form"
              disabled={locked || (!dirty && !!row)}><Save size={16} />{t('save')}</Button
            >{/if}
          {#if failure}<p class="editor-error" role="alert">
              <AlertCircle size={16} />{failure}
            </p>{/if}
          <Button variant="outline" href={back()}>{t('cancel')}</Button>
          {#if row && phase === 'editing'}<Button
              variant="destructive"
              disabled={locked}
              onclick={() => (confirmDelete = true)}
              ><Trash2 size={16} />{t('delete_record')}</Button
            >{/if}
        </div>
      </section>
      {#if row}<Media snapshot={original} />{/if}
    </aside>
  </div>
  {#if phase === 'conflict' && result?.current}<section
      class="panel conflict-panel"
      aria-label={t('resolve_conflict')}
    >
      <div class="panel-heading">
        <h2>{t('resolve_conflict')}</h2>
        <span>{t('current_version', { version: String(result.current.version) })}</span>
      </div>
      <div class="panel-body">
        <p>
          {frozen?.method === 'DELETE'
            ? t('delete_conflict_hint')
            : result.current.deletedAtMs
              ? t('deleted_conflict_hint')
              : t('conflict_choice_hint')}
        </p>
        <div class="table-wrap">
          <table class="data-table conflict-table">
            <thead
              ><tr
                ><th>{t('field')}</th><th>{t('edit_base')}</th><th>{t('your_edit')}</th><th
                  >{t('current_cloud_content')}</th
                ></tr
              ></thead
            ><tbody
              >{#each differences as key (key)}<tr
                  ><td>{fieldLabel(table, key)}</td><td data-label={t('edit_base')}
                    >{valueText(
                      table,
                      key,
                      original[key],
                      Object.hasOwn(original, key),
                      original,
                    )}</td
                  ><td data-label={t('your_edit')}
                    >{frozen?.method === 'DELETE'
                      ? t('delete_record')
                      : valueText(
                          table,
                          key,
                          candidate[key],
                          Object.hasOwn(candidate, key),
                          candidate,
                        )}</td
                  ><td data-label={t('current_cloud_content')}
                    >{result.current.deletedAtMs
                      ? t('deleted_dbdda0')
                      : valueText(
                          table,
                          key,
                          result.current.snapshot?.[key],
                          Object.hasOwn(result.current.snapshot ?? {}, key),
                          result.current.snapshot,
                        )}</td
                  ></tr
                >{/each}</tbody
            >
          </table>
        </div>
        {#if !differences.length}<p>{t('record_state_conflict')}</p>{/if}
        <div class="editor-actions">
          <Button
            variant={frozen?.method === 'DELETE' ? 'destructive' : 'default'}
            disabled={!session.online || !session.canWrite(table)}
            onclick={() => resolve('local')}
            >{frozen?.method === 'DELETE'
              ? t('confirm_delete')
              : result.current.deletedAtMs
                ? t('restore_with_your_edit')
                : t('keep_your_conflicting_values')}</Button
          ><Button
            variant="outline"
            disabled={!session.online || !session.canWrite(table)}
            onclick={() => resolve('remote')}>{t('keep_cloud_conflicting_values')}</Button
          >
        </div>
        <p class="editor-hint">{t('conflict_version_checked_again')}</p>
      </div>
    </section>{/if}
{/if}

<Dialog.Root bind:open={confirmDelete}
  ><Dialog.Content
    ><Dialog.Header
      ><Dialog.Title>{t('delete_record')}</Dialog.Title><Dialog.Description
        >{t('delete_record_confirmation', { title: title(original, id) })}</Dialog.Description
      ></Dialog.Header
    ><Dialog.Footer
      ><Button variant="outline" onclick={() => (confirmDelete = false)}>{t('cancel')}</Button
      ><Button
        variant="destructive"
        onclick={() => {
          confirmDelete = false;
          void submit('DELETE');
        }}>{t('confirm_delete')}</Button
      ></Dialog.Footer
    ></Dialog.Content
  ></Dialog.Root
>
<Dialog.Root bind:open={confirmLeave}
  ><Dialog.Content
    ><Dialog.Header
      ><Dialog.Title>{t('leave_editor')}</Dialog.Title><Dialog.Description
        >{phase === 'unknown' || phase === 'sending'
          ? t('leave_pending_write_hint')
          : t('discard_input_hint')}</Dialog.Description
      ></Dialog.Header
    ><Dialog.Footer
      ><Button variant="outline" onclick={() => (confirmLeave = false)}>{t('stay_on_page')}</Button
      ><Button
        onclick={() => {
          leaving = true;
          confirmLeave = false;
          void goto(leaveTo);
        }}>{t('leave_page')}</Button
      ></Dialog.Footer
    ></Dialog.Content
  ></Dialog.Root
>

<style>
  .editor-layout {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 320px;
    gap: 24px;
    align-items: start;
  }
  .editor-form {
    padding: 24px;
  }
  fieldset {
    border: 0;
    padding: 0;
    margin: 0 0 28px;
    min-width: 0;
  }
  legend {
    font-size: 1rem;
    font-weight: 600;
    padding: 0 0 16px;
  }
  .editor-fields {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 20px 24px;
  }
  .editor-save {
    display: grid;
    gap: 16px;
  }
  .editor-save p {
    font-size: 0.8125rem;
    overflow-wrap: anywhere;
  }
  .editor-save h2 {
    font-size: 1rem;
    margin-bottom: 8px;
  }
  .editor-hint {
    font-size: 0.75rem;
    color: var(--muted-foreground);
    margin-top: 16px;
  }
  .editor-error {
    color: var(--danger);
    display: flex;
    gap: 8px;
  }
  .editor-error :global(svg) {
    flex-shrink: 0;
    margin-top: 2px;
  }
  .editor-actions {
    display: flex;
    gap: 12px;
    flex-wrap: wrap;
  }
  .preserved-fields {
    margin-top: 24px;
    border-top: 1px solid var(--border);
    padding-top: 16px;
    font-size: 0.8125rem;
  }
  .preserved-content {
    padding-top: 16px;
  }
  .conflict-panel {
    margin-top: 24px;
  }
  .conflict-panel .panel-body > p {
    font-size: 0.8125rem;
    margin-bottom: 16px;
    max-width: 75ch;
  }
  .conflict-panel .panel-heading span {
    font-size: 0.75rem;
    color: var(--muted-foreground);
  }
  .conflict-table {
    margin: 16px 0 24px;
    table-layout: fixed;
  }
  .conflict-table td {
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    vertical-align: top;
  }
  @media (max-width: 900px) {
    .editor-layout {
      grid-template-columns: 1fr;
    }
    .editor-sidebar {
      grid-row: 1;
    }
  }
  @media (max-width: 600px) {
    .editor-fields {
      grid-template-columns: 1fr;
    }
    .editor-form {
      padding: 16px;
    }
  }
</style>
