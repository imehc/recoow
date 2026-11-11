import { preferences } from './preferences.svelte.js';

type Labels = Record<string, readonly [string, string]>;
// 取值与中文文案对应 iOS 的模型枚举和 Android 的资源文件。
const payment: Labels = {
  wechat: ['微信', 'WeChat'],
  alipay: ['支付宝', 'Alipay'],
  douyinPay: ['抖音', 'Douyin'],
  unionPay: ['云闪付', 'UnionPay'],
  bankCard: ['银行卡', 'Bank card'],
  cash: ['现金', 'Cash'],
  creditCard: ['信用卡', 'Credit card'],
  other: ['其他', 'Other'],
};
const expenseCategory: Labels = {
  dining: ['餐饮', 'Dining'],
  shopping: ['购物', 'Shopping'],
  transport: ['交通', 'Transport'],
  groupBuy: ['团购', 'Group purchase'],
  housing: ['住房', 'Housing'],
  utility: ['生活缴费', 'Utilities'],
  entertainment: ['娱乐', 'Entertainment'],
  medical: ['医疗', 'Medical'],
  learning: ['学习', 'Learning'],
  social: ['人情', 'Gifts'],
  other: ['其他', 'Other'],
};
const incomeCategory: Labels = {
  salary: ['工资', 'Salary'],
  bonus: ['奖金', 'Bonus'],
  investmentDividend: ['理财分红', 'Investment dividends'],
  appPayout: ['App活动', 'App rewards'],
  partTime: ['兼职副业', 'Side income'],
  reimbursement: ['报销', 'Reimbursement'],
  refund: ['退款', 'Refund'],
  other: ['其他收入', 'Other income'],
};
const mediaKind: Labels = { photo: ['照片', 'Photo'], audio: ['语音', 'Audio'] };
const mappings: Record<string, Labels> = {
  'bills.payment_method': payment,
  'bills.transaction_type': {
    expense: ['支出', 'Expense'],
    income: ['收入', 'Income'],
    storedValueUse: ['储值消费', 'Stored value use'],
  },
  'bills.settlement_status': {
    active: ['正常', 'Active'],
    redeemed: ['已核销', 'Redeemed'],
    refunded: ['已退款', 'Refunded'],
  },
  'stored_value_accounts.kind': {
    transitCard: ['交通卡', 'Transit card'],
    mealCard: ['饭卡', 'Meal card'],
    membershipCard: ['会员储值卡', 'Membership card'],
    giftCard: ['礼品卡', 'Gift card'],
    phoneBalance: ['话费余额', 'Phone balance'],
    other: ['其他储值', 'Other stored value'],
  },
  'food_entries.meal_kind': {
    breakfast: ['早餐', 'Breakfast'],
    lunch: ['午餐', 'Lunch'],
    dinner: ['晚餐', 'Dinner'],
    lateNightSnack: ['夜宵', 'Late-night snack'],
    snack: ['零食', 'Snack'],
    drink: ['饮品', 'Drink'],
    other: ['其他', 'Other'],
  },
  'diary_entries.mood': {
    neutral: ['平常', 'Neutral'],
    happy: ['开心', 'Happy'],
    calm: ['平静', 'Calm'],
    tired: ['疲惫', 'Tired'],
    stressed: ['有压力', 'Stressed'],
    sad: ['低落', 'Sad'],
  },
  'anniversaries.category': {
    birthday: ['生日', 'Birthday'],
    relationship: ['恋爱/婚姻', 'Relationship'],
    family: ['家庭', 'Family'],
    workStudy: ['工作/学习', 'Work/study'],
    personalGoal: ['个人目标', 'Personal goal'],
    other: ['其他', 'Other'],
  },
  'anniversaries.date_calendar': { gregorian: ['公历', 'Gregorian'], chinese: ['农历', 'Lunar'] },
  'reminders.schedule_kind': {
    single: ['单次打卡', 'Single check-in'],
    dateRange: ['阶段打卡', 'Date range'],
    weekdays: ['工作日打卡', 'Weekday check-ins'],
    weekly: ['每周打卡', 'Weekly check-ins'],
    continuousDays: ['连续挑战', 'Continuous challenge'],
    dailyGoal: ['坚持目标', 'Daily goal'],
  },
  'reminder_completions.kind': { checkIn: ['打卡', 'Check-in'], makeUp: ['补签', 'Make-up'] },
  'tracks.status': {
    recording: ['记录中', 'Recording'],
    paused: ['已暂停', 'Paused'],
    finished: ['已完成', 'Finished'],
  },
  'track_segments.motion_type': {
    unknown: ['未识别', 'Unidentified'],
    stationary: ['静止', 'Stationary'],
    walking: ['走路', 'Walking'],
    running: ['跑步', 'Running'],
    cycling: ['骑行', 'Cycling'],
    transit: ['公共交通', 'Public transport'],
    driving: ['开车', 'Driving'],
  },
  'track_segments.source': { auto: ['自动', 'Automatic'], manual: ['手动', 'Manual'] },
  'diary_links.source_type': {
    track: ['轨迹记录', 'Track recording'],
    bill: ['记一笔', 'Bills'],
    reminder: ['打卡任务', 'Check-ins'],
    anniversary: ['纪念日', 'Anniversaries'],
    storedItem: ['在哪里', 'Where is it'],
    decisionChoice: ['选什么', 'What to choose'],
  },
  'media_assets.kind': mediaKind,
  'media_attachments.kind': mediaKind,
  'media_attachments.owner_type': {
    diary: ['日记', 'Diary'],
    foodEntry: ['饮食记录', 'Food journal'],
  },
  'media_assets.storage_backend': { local: ['本地', 'Local'] },
};

export function clientValue(
  table: string,
  key: string,
  value: string,
  snapshot?: Record<string, unknown> | null,
): string | undefined {
  const values =
    table === 'bills' && key === 'category'
      ? snapshot?.transaction_type === 'income'
        ? incomeCategory
        : expenseCategory
      : mappings[`${table}.${key}`];
  return values?.[value]?.[preferences.locale === 'zh' ? 0 : 1];
}
