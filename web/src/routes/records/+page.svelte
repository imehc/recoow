<script lang="ts">
  import {
    BookOpen,
    Wallet,
    Package,
    Utensils,
    CalendarDays,
    MapPin,
    Shuffle,
    Image,
    CircleCheck,
    ArrowRight,
  } from '@lucide/svelte';
  import { recordGroups, groupTitle } from '#lib/record-groups.js';
  import { entityTitle } from '#lib/format.js';
  import { t } from '#lib/preferences.svelte.js';
  import PageHeader from '#lib/components/PageHeader.svelte';
  const icons = [
    MapPin,
    Shuffle,
    Package,
    CircleCheck,
    Wallet,
    Utensils,
    BookOpen,
    CalendarDays,
    Image,
  ];
</script>

<PageHeader title={t('records')} description={t('choose_record_category')} />
<div class="record-directory">
  {#each recordGroups as group, index (group.id)}
    {@const Icon = icons[index]}
    <section class="panel directory-group">
      <div class="panel-heading">
        <a class="directory-title" href={'/records/' + group.tables[0]}
          ><Icon size={20} />
          <h2>{groupTitle(group)}</h2></a
        ><a
          class="text-link"
          href={'/records/' + group.tables[0]}
          aria-label={t('open_category', { category: groupTitle(group) })}
          ><ArrowRight size={16} /></a
        >
      </div>
      <div class="directory-links">
        {#each group.tables as table (table)}<a href={'/records/' + table}
            >{entityTitle(table)}<ArrowRight size={14} /></a
          >{/each}
      </div>
    </section>
  {/each}
</div>
