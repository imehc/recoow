<script lang="ts">
  import { Calendar, Popover } from 'bits-ui';
  import { CalendarDays, ChevronLeft, ChevronRight, X } from '@lucide/svelte';
  import { parseDate, type DateValue } from '@internationalized/date';
  import { preferences, t } from '#lib/preferences.svelte.js';
  let {
    label,
    value = $bindable(''),
    min,
    max,
    hint,
    disabled = false,
    onchange,
  }: {
    label: string;
    value?: string;
    min?: string;
    max?: string;
    hint?: string;
    disabled?: boolean;
    onchange?: (value: string) => void;
  } = $props();
  let open = $state(false);
  const id = $props.id();
  function date(raw?: string): DateValue | undefined {
    try {
      return raw ? parseDate(raw) : undefined;
    } catch {
      return undefined;
    }
  }
</script>

<div class="form-field date-field">
  <span class="field-label" {id}>{label}</span>
  <Popover.Root bind:open>
    <div class="date-control">
      <Popover.Trigger {disabled} class="date-picker-trigger" aria-labelledby={id}>
        <span class:placeholder={!value}>
          {value ? value.replaceAll('-', '/') : t('select_date')}
        </span>
        <CalendarDays size={16} />
      </Popover.Trigger>
      {#if value}
        <button
          type="button"
          class="date-clear"
          aria-label={t('clear_date', { label })}
          {disabled}
          onclick={() => {
            value = '';
            onchange?.(value);
          }}
        >
          <X size={13} />
        </button>
      {/if}
    </div>
    <Popover.Portal>
      <Popover.Content class="calendar-popover" align="start" sideOffset={6} collisionPadding={12}>
        <Calendar.Root
          type="single"
          locale={preferences.locale === 'zh' ? 'zh-CN' : 'en-GB'}
          value={date(value)}
          minValue={date(min)}
          maxValue={date(max)}
          weekStartsOn={1}
          fixedWeeks
          preventDeselect
          onValueChange={(next) => {
            if (next) {
              value = next.toString();
              onchange?.(value);
              open = false;
            }
          }}
        >
          {#snippet children({ months, weekdays })}
            <Calendar.Header class="calendar-header">
              <Calendar.PrevButton class="calendar-nav">
                <ChevronLeft size={16} />
              </Calendar.PrevButton>
              <Calendar.Heading class="calendar-heading" />
              <Calendar.NextButton class="calendar-nav">
                <ChevronRight size={16} />
              </Calendar.NextButton>
            </Calendar.Header>
            {#each months as month (month.value.toString())}
              <Calendar.Grid class="calendar-grid">
                <Calendar.GridHead>
                  <Calendar.GridRow>
                    {#each weekdays as day, i (i)}
                      <Calendar.HeadCell>{day}</Calendar.HeadCell>
                    {/each}
                  </Calendar.GridRow>
                </Calendar.GridHead>
                <Calendar.GridBody>
                  {#each month.weeks as week, i (i)}
                    <Calendar.GridRow>
                      {#each week as day (day.toString())}
                        <Calendar.Cell date={day} month={month.value}>
                          <Calendar.Day class="calendar-day" />
                        </Calendar.Cell>
                      {/each}
                    </Calendar.GridRow>
                  {/each}
                </Calendar.GridBody>
              </Calendar.Grid>
            {/each}
          {/snippet}
        </Calendar.Root>
        {#if hint}
          <p class="calendar-hint">{hint}</p>
        {/if}
      </Popover.Content>
    </Popover.Portal>
  </Popover.Root>
</div>
