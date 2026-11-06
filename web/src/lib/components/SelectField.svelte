<script lang="ts">
  import { Select } from 'bits-ui';
  import { Check, ChevronDown } from '@lucide/svelte';
  let {
    label,
    value = $bindable(''),
    options,
    disabled = false,
    onchange,
  }: {
    label: string;
    value?: string;
    options: { value: string; label: string; group?: string }[];
    disabled?: boolean;
    onchange?: (value: string) => void;
  } = $props();
  const id = $props.id();
  const selected = $derived(options.find((option) => option.value === value)?.label ?? '');
  const groups = $derived([...new Set(options.map((option) => option.group ?? ''))]);
</script>

<div class="form-field">
  <label for={id}>{label}</label>
  <Select.Root
    type="single"
    {value}
    {disabled}
    items={options}
    onValueChange={(next) => {
      value = next;
      onchange?.(next);
    }}
  >
    <Select.Trigger {id} class="select-trigger" aria-label={label}>
      <span>{selected}</span><ChevronDown size={14} />
    </Select.Trigger>
    <Select.Portal>
      <Select.Content
        class="select-menu"
        sideOffset={6}
        collisionPadding={12}
        preventScroll={false}
      >
        <Select.Viewport class="select-viewport">
          {#each groups as group (group)}<Select.Group>
              {#if group}<Select.GroupHeading class="select-group-heading"
                  >{group}</Select.GroupHeading
                >{/if}
              {#each options.filter((option) => (option.group ?? '') === group) as option (option.value)}
                <Select.Item value={option.value} label={option.label} class="select-option">
                  {#snippet children({ selected })}<span>{option.label}</span>{#if selected}<Check
                        size={15}
                      />{/if}{/snippet}
                </Select.Item>
              {/each}
            </Select.Group>{/each}
        </Select.Viewport>
      </Select.Content>
    </Select.Portal>
  </Select.Root>
</div>
