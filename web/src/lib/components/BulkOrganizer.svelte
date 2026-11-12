<script lang="ts">
  import { onMount, untrack } from 'svelte';
  import { SvelteMap } from 'svelte/reactivity';
  import { beforeNavigate, goto } from '$app/navigation';
  import { api, APIError } from '#lib/api.js';
  import { session, queryClient } from '#lib/session.svelte.js';
  import { prepareBulk, type BulkAction, type BulkItem } from '#lib/bulk-organizer.js';
  import { clientOptions } from '#lib/client-values.js';
  import { title, fieldLabel, valueText } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  import { appPath } from '#lib/paths.js';
  import type { Row } from '#lib/generated/api/models/Row.js';
  import RecordReference from './RecordReference.svelte';
  import SelectField from './SelectField.svelte';
  import { Button } from './ui/button/index.js';
  import * as Dialog from './ui/dialog/index.js';
  let { table, rows, onclose }: { table: string; rows: Row[]; onclose: () => void } = $props();
  const generation = untrack(() => session.generation);
  let operation = $state('add_tag');
  let value = $state('');
  let key = $state('');
  let tagName = $state('');
  let items = $state<BulkItem[]>([]);
  let busy = $state(false);
  let message = $state('');
  let confirmLeave = $state(false);
  let leaveTo = $state('');
  let leaving = $state(false);
  const stateLabels = {
    ready: 'bulk_state_ready',
    sending: 'bulk_state_sending',
    saved: 'bulk_state_saved',
    unchanged: 'bulk_state_unchanged',
    invalid: 'bulk_state_invalid',
    failed: 'bulk_state_failed',
    conflict: 'bulk_state_conflict',
    unknown: 'bulk_state_unknown',
  } as const;
  const states = [
    'saved',
    'unchanged',
    'invalid',
    'failed',
    'conflict',
    'unknown',
    'ready',
  ] as const;
  const pending = $derived(
    items.some((item) => ['ready', 'sending', 'unknown'].includes(item.state)),
  );
  const protectedInput = $derived(!leaving && (pending || !items.length));
  const unknown = $derived(items.some((item) => item.state === 'unknown'));
  const totals = $derived(
    states.map((state) => ({
      state,
      count: items.filter((item) => item.state === state).length,
    })),
  );
  const categoryOptions = $derived.by(() => {
    const options: { value: string; label: string }[] = [];
    for (const row of rows) {
      for (const option of clientOptions('bills', 'category', row.snapshot))
        if (!options.some((item) => item.value === option.value)) options.push(option);
      const category = row.snapshot?.category;
      if (
        typeof category === 'string' &&
        category &&
        !options.some((item) => item.value === category)
      )
        options.push({
          value: category,
          label: valueText('bills', 'category', category, true, row.snapshot),
        });
    }
    return options;
  });
  const removableTags = $derived.by(() => {
    const tags = new SvelteMap<string, string>();
    for (const row of rows) {
      try {
        for (const tag of JSON.parse(String(row.snapshot?.tags_json ?? '[]')))
          if (tag && typeof tag.key === 'string' && typeof tag.value === 'string')
            tags.set(tag.key, tag.value);
      } catch {
        /* 无法解析的记录在预览中逐条报告。 */
      }
    }
    return [...tags].map(([value, name]) => ({ value, name, label: `${name} · ${value}` }));
  });
  let controller: AbortController | undefined;
  beforeNavigate((navigation) => {
    if (!protectedInput || !navigation.to) return;
    navigation.cancel();
    if (!navigation.willUnload) {
      leaveTo = navigation.to.url.href;
      confirmLeave = true;
    }
  });
  onMount(() => {
    const unload = (event: BeforeUnloadEvent) => {
      if (protectedInput) event.preventDefault();
    };
    window.addEventListener('beforeunload', unload);
    return () => {
      controller?.abort();
      window.removeEventListener('beforeunload', unload);
    };
  });
  function preview() {
    message = '';
    let action: BulkAction;
    if (table === 'diary_entries') {
      const name =
        operation === 'remove_tag'
          ? (removableTags.find((tag) => tag.value === key)?.name ?? '')
          : tagName || value.trim();
      if (!name || (operation === 'remove_tag' && !key)) {
        message = t('choose_a_tag');
        return;
      }
      action = {
        kind: operation === 'remove_tag' ? 'remove_tag' : 'add_tag',
        key: key || crypto.randomUUID(),
        value: name,
      };
    } else {
      if (!value.trim()) {
        message = t('enter_bulk_value');
        return;
      }
      action = { kind: table === 'stored_items' ? 'location' : 'category', value };
    }
    try {
      items = prepareBulk(table, rows, action, generation);
    } catch {
      message = t('bulk_limit_hint');
    }
  }
  async function save() {
    if (busy || !session.online || !session.canWrite(table) || session.generation !== generation)
      return;
    busy = true;
    message = '';
    controller = new AbortController();
    const signal = controller.signal;
    try {
      for (const item of items) {
        if (!['ready', 'unknown'].includes(item.state) || !item.write) continue;
        if (!session.online || !session.canWrite(table)) {
          message = t('bulk_paused');
          break;
        }
        item.state = 'sending';
        try {
          const result = await api.write(
            item.write.table,
            item.write.id,
            item.write.method,
            item.write.body,
            signal,
          );
          signal.throwIfAborted();
          if (
            result.id !== item.write.id ||
            result.table !== item.write.table ||
            ![
              'applied',
              'duplicate',
              'conflict',
              'dependency_missing',
              'invalid_revision',
              'mutation_reused',
              'unsupported_table',
              'invalid_operation',
            ].includes(result.status)
          )
            throw new Error(t('unable_to_verify_write_receipt'));
          item.result = result;
          item.state = ['applied', 'duplicate'].includes(result.status ?? '')
            ? 'saved'
            : result.status === 'conflict'
              ? 'conflict'
              : 'failed';
          if (item.state === 'failed')
            item.error = t(
              result.status === 'dependency_missing'
                ? 'dependency_missing_write'
                : 'revision_rejected',
            );
        } catch (error) {
          if (signal.aborted) return;
          if (error instanceof APIError && [400, 401, 403, 404, 422].includes(error.status)) {
            item.state = 'failed';
            item.error = error.message;
            if ([401, 403].includes(error.status)) {
              message = t('bulk_paused');
              break;
            }
          } else {
            item.state = 'unknown';
            item.error = error instanceof Error ? error.message : '';
            message = t('bulk_unknown_hint');
            break;
          }
        }
      }
      await queryClient.invalidateQueries();
    } finally {
      if (!signal.aborted) busy = false;
    }
  }
  function close() {
    if (protectedInput) {
      leaveTo = '';
      confirmLeave = true;
    } else onclose();
  }
  function previewValue(field: string, value: unknown, present = true) {
    if (field === 'tags_json' && typeof value === 'string') {
      try {
        const tags: unknown = JSON.parse(value);
        if (Array.isArray(tags) && tags.every((tag) => typeof tag?.value === 'string'))
          return tags.length ? tags.map((tag) => tag.value).join('、') : t('no_tags');
      } catch {
        // 非标准标签仍保留原文，避免隐藏无法识别的内容。
      }
    }
    return valueText(table, field, value, present);
  }
</script>

<section class="bulk-organizer" aria-label={t('bulk_organize')}>
  <div class="panel-heading">
    <h2>{t('bulk_organize')}</h2>
    <Button variant="ghost" disabled={busy} onclick={close}>{t('close')}</Button>
  </div>
  <div class="panel-body">
    <p class="muted">{t('bulk_targets', { count: rows.length })}</p>
    {#if !items.length}
      <div class="bulk-fields">
        {#if table === 'diary_entries'}
          <SelectField
            label={t('bulk_action')}
            bind:value={operation}
            options={[
              { value: 'add_tag', label: t('bulk_add_tag') },
              { value: 'remove_tag', label: t('bulk_remove_tag') },
            ]}
            onchange={() => {
              key = '';
              tagName = '';
            }}
          />
          {#if operation === 'add_tag'}
            <RecordReference
              table="diary_tags"
              label={t('existing_tags')}
              bind:value={key}
              onselect={(row) => {
                tagName = typeof row?.snapshot?.name === 'string' ? row.snapshot.name : '';
              }}
            />
            <label class="form-field">
              {t('new_tag_name')}
              <input bind:value disabled={!!key} />
            </label>
          {:else}
            <SelectField label={t('tag_to_remove')} bind:value={key} options={removableTags} />
          {/if}
        {:else if table === 'stored_items'}
          <label class="form-field">
            {fieldLabel(table, 'location')}
            <input bind:value />
          </label>
        {:else}
          <SelectField label={fieldLabel(table, 'category')} bind:value options={categoryOptions} />
        {/if}
        <Button onclick={preview}>{t('preview_changes')}</Button>
      </div>
    {:else}
      <div class="bulk-summary" role="status">
        {#each totals.filter((item) => item.count) as total (total.state)}
          <span>{t(stateLabels[total.state])}: {total.count}</span>
        {/each}
      </div>
      <div class="bulk-results">
        {#each items as item (item.row.id)}
          <div class="bulk-result">
            <div class="bulk-result-heading">
              <a
                class="record-title"
                href={appPath('/records/' + table + '/' + encodeURIComponent(item.row.id))}
              >
                {title(item.row.snapshot, item.row.id)}
              </a>
              <span>{t(stateLabels[item.state])}</span>
            </div>
            {#if item.candidate}
              <dl class="bulk-diff">
                <div>
                  <dt>{t('before')}</dt>
                  <dd>
                    {previewValue(
                      item.field,
                      item.row.snapshot?.[item.field],
                      Object.hasOwn(item.row.snapshot ?? {}, item.field),
                    )}
                  </dd>
                </div>
                <div>
                  <dt>{t('after')}</dt>
                  <dd>{previewValue(item.field, item.candidate[item.field])}</dd>
                </div>
              </dl>
            {/if}
            {#if item.state === 'invalid'}
              <p class="error-copy">{t('bulk_invalid_hint')}</p>
            {/if}
            {#if item.state === 'failed'}
              <p class="error-copy">
                {item.error || t('bulk_failed_hint')}
              </p>
            {/if}
            {#if item.state === 'conflict'}
              <p>
                {t('bulk_conflict_hint')}
                <a
                  class="text-link"
                  href={appPath(
                    '/records/' + table + '/' + encodeURIComponent(item.row.id) + '/edit',
                  )}
                >
                  {t('edit_record')}
                </a>
              </p>
            {/if}
          </div>
        {/each}
      </div>
      <div class="bulk-actions">
        <Button
          disabled={busy || !pending || !session.online || !session.canWrite(table)}
          onclick={save}
        >
          {busy ? t('saving') : unknown ? t('retry') : t('save_bulk_changes')}
        </Button>
        {#if !items.some( (item) => ['sending', 'saved', 'failed', 'conflict', 'unknown'].includes(item.state) )}
          <Button
            variant="outline"
            onclick={() => {
              items = [];
            }}
          >
            {t('adjust_changes')}
          </Button>
        {/if}
      </div>
    {/if}
    {#if message}
      <p class="bulk-feedback" role="alert">{message}</p>
    {/if}
    {#if !session.online}
      <p role="status">{t('bulk_paused')}</p>
    {/if}
  </div>
</section>
<Dialog.Root bind:open={confirmLeave}>
  <Dialog.Content>
    <Dialog.Header>
      <Dialog.Title>{t('leave_editor')}</Dialog.Title>
      <Dialog.Description>{t('bulk_leave_hint')}</Dialog.Description>
    </Dialog.Header>
    <Dialog.Footer>
      <Button
        variant="outline"
        onclick={() => {
          confirmLeave = false;
        }}
      >
        {t('continue_editing')}
      </Button>
      <Button
        variant="destructive"
        onclick={() => {
          leaving = true;
          controller?.abort();
          confirmLeave = false;
          if (leaveTo) void goto(leaveTo);
          else onclose();
        }}
      >
        {t('leave_page')}
      </Button>
    </Dialog.Footer>
  </Dialog.Content>
</Dialog.Root>
