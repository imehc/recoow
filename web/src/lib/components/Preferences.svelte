<script lang="ts">
  import { DropdownMenu } from 'bits-ui';
  import { Check, Globe, Languages, Monitor, Moon, Sun } from '@lucide/svelte';
  import { Button } from '#lib/components/ui/button/index.js';
  import { preferences, t, type Language, type Theme } from '#lib/preferences.svelte.js';
  const themes = $derived([
    { value: 'system', label: t('system'), icon: Monitor },
    { value: 'light', label: t('light'), icon: Sun },
    { value: 'dark', label: t('dark'), icon: Moon },
  ]);
  const languages = $derived([
    { value: 'system', label: t('system'), icon: Globe },
    { value: 'zh', label: '简体中文', icon: Languages },
    { value: 'en', label: 'English', icon: Languages },
  ]);
  const selectedTheme = $derived(
    themes.find((item) => item.value === preferences.theme) ?? themes[0],
  );
  const selectedLanguage = $derived(
    languages.find((item) => item.value === preferences.language) ?? languages[0],
  );
</script>

<div class="preferences">
  <DropdownMenu.Root>
    <DropdownMenu.Trigger>
      {#snippet child({ props })}
        <Button
          {...props}
          variant="ghost"
          size="icon"
          class="preference-trigger"
          aria-label={t('theme')}
          title={t('theme') + ' · ' + selectedTheme.label}
        >
          <selectedTheme.icon size={18} />
        </Button>
      {/snippet}
    </DropdownMenu.Trigger>
    <DropdownMenu.Portal>
      <DropdownMenu.Content
        class="preference-menu"
        align="end"
        sideOffset={8}
        collisionPadding={12}
        loop
        aria-label={t('theme')}
      >
        <div class="preference-menu-heading" aria-hidden="true">{t('appearance')}</div>
        <DropdownMenu.RadioGroup
          value={preferences.theme}
          onValueChange={(value) => preferences.setTheme(value as Theme)}
        >
          {#each themes as item (item.value)}
            <DropdownMenu.RadioItem
              class="preference-option"
              value={item.value}
              textValue={item.label}
            >
              {#snippet children({ checked })}
                <item.icon size={17} />
                <span>{item.label}</span>
                <span class="preference-check">
                  {#if checked}
                    <Check size={16} />
                  {/if}
                </span>
              {/snippet}
            </DropdownMenu.RadioItem>
          {/each}
        </DropdownMenu.RadioGroup>
      </DropdownMenu.Content>
    </DropdownMenu.Portal>
  </DropdownMenu.Root>
  <DropdownMenu.Root>
    <DropdownMenu.Trigger>
      {#snippet child({ props })}
        <Button
          {...props}
          variant="ghost"
          size="icon"
          class="preference-trigger"
          aria-label={t('language')}
          title={t('language') + ' · ' + selectedLanguage.label}
        >
          <Languages size={18} />
        </Button>
      {/snippet}
    </DropdownMenu.Trigger>
    <DropdownMenu.Portal>
      <DropdownMenu.Content
        class="preference-menu"
        align="end"
        sideOffset={8}
        collisionPadding={12}
        loop
        aria-label={t('language')}
      >
        <div class="preference-menu-heading" aria-hidden="true">{t('display_language')}</div>
        <DropdownMenu.RadioGroup
          value={preferences.language}
          onValueChange={(value) => preferences.setLanguage(value as Language)}
        >
          {#each languages as item (item.value)}
            <DropdownMenu.RadioItem
              class="preference-option"
              value={item.value}
              textValue={item.label}
            >
              {#snippet children({ checked })}
                <item.icon size={17} />
                <span>{item.label}</span>
                <span class="preference-check">
                  {#if checked}
                    <Check size={16} />
                  {/if}
                </span>
              {/snippet}
            </DropdownMenu.RadioItem>
          {/each}
        </DropdownMenu.RadioGroup>
      </DropdownMenu.Content>
    </DropdownMenu.Portal>
  </DropdownMenu.Root>
</div>
