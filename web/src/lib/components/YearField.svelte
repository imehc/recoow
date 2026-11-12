<script lang="ts">
  import { Popover } from 'bits-ui';
  import { ChevronDown, ChevronLeft, ChevronRight } from '@lucide/svelte';
  import { t } from '#lib/preferences.svelte.js';
  let {
    label,
    value = $bindable(new Date().getFullYear()),
    min,
    max,
  }: {
    label: string;
    value?: number;
    min: number;
    max: number;
  } = $props();
  const id = $props.id();
  let open = $state(false);
  let start = $state(0);
  const end = $derived(Math.min(start + 11, max));
  const years = $derived(Array.from({ length: end - start + 1 }, (_, i) => start + i));
</script>

<div class="form-field">
  <label for={id}>{label}</label>
  <Popover.Root
    bind:open
    onOpenChange={(next) => {
      if (next) start = Math.max(min, Math.min(max - 11, Math.floor(value / 12) * 12));
    }}
  >
    <Popover.Trigger {id} class="select-trigger" aria-label={label}>
      <span>{value}</span>
      <ChevronDown size={14} />
    </Popover.Trigger>
    <Popover.Portal>
      <Popover.Content class="calendar-popover" align="start" sideOffset={6} collisionPadding={12}>
        <div class="calendar-header">
          <button
            type="button"
            class="calendar-nav"
            aria-label={t('previous_year_range')}
            disabled={start <= min}
            onclick={() => (start = Math.max(min, start - 12))}
          >
            <ChevronLeft size={16} />
          </button>
          <span class="calendar-heading">{start} – {end}</span>
          <button
            type="button"
            class="calendar-nav"
            aria-label={t('next_year_range')}
            disabled={end >= max}
            onclick={() => (start = Math.min(max - 11, start + 12))}
          >
            <ChevronRight size={16} />
          </button>
        </div>
        <div class="year-grid">
          {#each years as year (year)}
            <button
              type="button"
              class="calendar-day"
              aria-pressed={year === value}
              onclick={() => {
                value = year;
                open = false;
              }}
            >
              {year}
            </button>
          {/each}
        </div>
      </Popover.Content>
    </Popover.Portal>
  </Popover.Root>
</div>
