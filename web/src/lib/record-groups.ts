import { preferences } from './preferences.svelte.js';

// 与 iOS 的 ToolRoute.title 和 Android 的 tool_* 保持一致；媒体在各工具间共享。
export const recordGroups = [
  {
    id: 'tracks',
    titles: { zh: '轨迹记录', en: 'Track recording' },
    tables: ['tracks', 'track_segments', 'track_points'],
  },
  {
    id: 'decisions',
    titles: { zh: '选什么', en: 'What to choose' },
    tables: ['decision_collections', 'decision_options', 'decision_choice_records'],
  },
  {
    id: 'items',
    titles: { zh: '在哪里', en: 'Where is it' },
    tables: ['stored_items', 'item_categories'],
  },
  {
    id: 'check-ins',
    titles: { zh: '打卡任务', en: 'Check-ins' },
    tables: ['reminders', 'reminder_completions'],
  },
  {
    id: 'finance',
    titles: { zh: '记一笔', en: 'Bills' },
    tables: ['bills', 'stored_value_accounts', 'stored_value_adjustments'],
  },
  {
    id: 'food',
    titles: { zh: '饮食记录', en: 'Food journal' },
    tables: ['food_day_records', 'food_entries'],
  },
  {
    id: 'diary',
    titles: { zh: '日记', en: 'Diary' },
    tables: ['diary_entries', 'diary_tags', 'diary_links'],
  },
  { id: 'anniversaries', titles: { zh: '纪念日', en: 'Anniversaries' }, tables: ['anniversaries'] },
  {
    id: 'media',
    titles: { zh: '素材库', en: 'Media library' },
    tables: ['media_assets', 'media_attachments'],
  },
];

export const recordGroup = (table: string) =>
  recordGroups.find((group) => group.tables.includes(table));
export const groupTitle = (group: (typeof recordGroups)[number]) =>
  group.titles[preferences.locale];

export const clientTypeTitles: Record<string, { zh: string; en: string }> = {
  decision_collections: { zh: '集合', en: 'Collections' },
  decision_options: { zh: '候选项', en: 'Options' },
  decision_choice_records: { zh: '抽选历史', en: 'Draw history' },
  reminders: { zh: '打卡任务', en: 'Check-ins' },
  reminder_completions: { zh: '打卡明细', en: 'Check-in history' },
  item_categories: { zh: '物品分类', en: 'Item categories' },
};
