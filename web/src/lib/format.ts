import { isLosslessNumber, stringify } from 'lossless-json';
import { preferences, t } from './preferences.svelte.js';
import { clientValue } from './client-values.js';
import { clientTypeTitles } from './record-groups.js';
import { entities } from './generated/catalog';

export { entities };
export const entity = (table: string) => entities.find((item) => item.table === table);
const labels: Record<string, string> = {
  server_updated_at_ms: '服务端更新时间',
  title: '标题',
  name: '名称',
  note: '备注',
  content: '内容',
  body: '正文',
  category: '分类',
  occurred_at: '发生时间',
  created_at: '创建时间',
  updated_at: '修改时间',
  amount_cents: '金额',
  original_amount_cents: '原价',
  discount_amount_cents: '优惠',
  final_amount_cents: '实付金额',
  balance_cents: '余额',
  transaction_type: '交易类型',
  payment_method: '支付方式',
  settlement_status: '结算状态',
  refund_reason: '退款原因',
  reason: '原因',
  redeemed_at: '核销时间',
  start_location: '出发地点',
  end_location: '到达地点',
  transport_lines: '交通线路',
  utility_account_number: '生活缴费户号',
  started_at: '开始时间',
  ended_at: '结束时间',
  timestamp_ms: '采集时间',
  completed_at: '完成时间',
  selected_at: '选择时间',
  storage_location: '存放位置',
  location: '位置',
  description: '描述',
  detail: '详情',
  tags: '标签',
  tags_json: '标签',
  category_id: '分类关联',
  diary_id: '日记关联',
  bill_id: '账单关联',
  bill_ids_json: '关联账单',
  track_id: '轨迹关联',
  account_id: '账户关联',
  image_asset_id: '图片关联',
  owner_id: '所属记录',
  owner_type: '所属类型',
  source_id: '来源记录',
  source_type: '来源类型',
  source_title: '来源标题',
  is_enabled: '是否启用',
  is_yearly: '每年重复',
  is_paused: '是否暂停',
  date_key: '日期',
  date_calendar: '日历类型',
  day_start_at: '日期',
  reminder_time_minutes: '提醒时间',
  lead_time_minutes: '提前提醒（分钟）',
  kind: '类型',
  meal_kind: '餐别',
  portion: '份量',
  mime_type: '媒体类型',
  byte_count: '文件大小（字节）',
  sort_order: '排序',
  checksum: '校验值',
  storage_key: '媒体引用',
  latitude: '纬度',
  longitude: '经度',
  altitude: '海拔',
  accuracy: '精度',
  speed: '速度',
  _media: '媒体引用',
  mood: '心情',
  collection_id: '集合关联',
  collection_title: '集合',
  option_id: '候选项关联',
  option_title: '候选项',
  option_detail: '候选项详情',
  option_custom_info: '候选项补充信息',
  option_image_asset_id: '候选项图片',
  custom_info: '补充信息',
  weight: '权重',
  source_subtitle: '来源摘要',
  source_icon: '来源图标',
  source_occurred_at: '来源时间',
  snapshot_json: '历史快照',
  storage_backend: '存储位置',
  asset_id: '素材关联',
  width: '宽度',
  height: '高度',
  duration_seconds: '时长（秒）',
  horizontal_accuracy: '水平精度',
  horizontal_acc: '水平精度',
  vertical_acc: '垂直精度',
  speed_mps: '速度（米/秒）',
  course_deg: '方向（度）',
  distance_m: '距离（米）',
  duration_s: '时长（秒）',
  avg_speed_mps: '平均速度（米/秒）',
  max_speed_mps: '最高速度（米/秒）',
  desired_accuracy_m: '目标精度（米）',
  motion_type: '运动方式',
  confidence: '可信度',
  status: '状态',
  search_keywords: '搜索关键词',
};

export function fieldLabel(table: string, key: string): string {
  const field = entity(table)?.fields.find((field) => field.name === key);
  return preferences.locale === 'en'
    ? ((key === 'server_updated_at_ms' ? 'Server updated at' : field?.labels.en) ??
        key.replaceAll('_', ' '))
    : (labels[key] ?? field?.labels.zh ?? key);
}
export function integer(value: unknown): bigint | null {
  const text = isLosslessNumber(value)
    ? value.value
    : typeof value === 'bigint'
      ? String(value)
      : typeof value === 'number' && Number.isSafeInteger(value)
        ? String(value)
        : '';
  return /^-?\d+$/.test(text) ? BigInt(text) : null;
}
export function money(value: unknown): string {
  const amount = integer(value);
  if (amount === null) return t('not_recorded');
  const absolute = amount < 0n ? -amount : amount;
  const whole = String(absolute / 100n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${amount < 0n ? '-' : ''}¥${whole}.${String(absolute % 100n).padStart(2, '0')}`;
}
export function time(value: unknown): string {
  const timestamp = Number(isLosslessNumber(value) ? value.value : value);
  if (value == null || !Number.isFinite(timestamp) || timestamp <= 0) return t('not_recorded');
  const date = new Date(timestamp);
  return Number.isNaN(date.getTime())
    ? t('not_recorded')
    : new Intl.DateTimeFormat(preferences.locale === 'zh' ? 'zh-CN' : 'en-GB', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false,
      }).format(date);
}
export const timezone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;
export function valueText(
  table: string,
  key: string,
  value: unknown,
  present = true,
  snapshot?: Record<string, unknown> | null,
): string {
  if (!present) return t('missing');
  if (value === null) return t('null');
  if (value === undefined) return t('not_recorded');
  if (key.endsWith('_cents')) return money(value);
  if (typeof value === 'string') {
    const label = clientValue(table, key, value, snapshot);
    if (label !== undefined) return label;
  }
  const field = entity(table)?.fields.find((field) => field.name === key);
  if (field?.format === 'ts_ms') return time(value);
  if (key === 'reminder_time_minutes') {
    const minutes = integer(value);
    if (minutes !== null && minutes >= 0n && minutes < 1440n)
      return `${String(minutes / 60n).padStart(2, '0')}:${String(minutes % 60n).padStart(2, '0')}`;
  }
  if (typeof value === 'boolean') return value ? t('yes') : t('no');
  if (isLosslessNumber(value)) return value.value;
  if (typeof value === 'object') return stringify(value, undefined, 2) ?? t('not_recorded');
  return String(value);
}
export function title(
  snapshot: Record<string, unknown> | null | undefined,
  fallback: string,
): string {
  for (const key of ['title', 'name', 'collection_title', 'option_title'])
    if (typeof snapshot?.[key] === 'string' && snapshot[key]) return snapshot[key] as string;
  return fallback;
}
export const operationText = (value: string) =>
  ({
    insert: t('added'),
    update: t('edited'),
    delete: t('deleted'),
    restore: t('restored'),
  })[value] ?? value;
export const methodText = (value: string) =>
  ({
    normal: t('normal_submission'),
    automatic_merge: t('automatic_merge'),
    manual_resolution: t('manual_resolution'),
    system: t('system_calculation'),
    unknown: t('not_recorded'),
  })[value] ?? value;
export function source(
  value: { client?: string; os?: string; deviceName?: string } | null | undefined,
): string {
  if (!value) return t('not_recorded');
  const client = (
    {
      ios: 'iOS App',
      android: 'Android App',
      web: 'Web',
      unknown: t('unknown_source'),
    } as Record<string, string>
  )[value.client ?? 'unknown'];
  const os =
    value.os && value.os !== 'unknown'
      ? (
          {
            ios: 'iOS',
            android: 'Android',
            macos: 'macOS',
            windows: 'Windows',
            linux: 'Linux',
          } as Record<string, string>
        )[value.os]
      : '';
  return [client, os, value.deviceName].filter(Boolean).join(' · ');
}
export function dateRange(from: string, to: string): Record<string, number | undefined> {
  const start = from ? new Date(`${from}T00:00:00`).getTime() : undefined;
  const end = to ? new Date(`${to}T00:00:00`) : undefined;
  if (end) end.setDate(end.getDate() + 1);
  return { fromMs: start, toMs: end?.getTime() };
}

export const entityTitle = (table: string) =>
  clientTypeTitles[table]?.[preferences.locale] ??
  entity(table)?.titles[preferences.locale] ??
  table;
