import { test, expect, type Page } from '@playwright/test';
import { appPath } from './paths.js';
import { apiFields } from '../../src/lib/api-fields.js';
import type { Row } from '../../src/lib/generated/api/models/Row.js';

const synthetic = (table: string, id: string, snapshot: Record<string, unknown>): Row => ({
  table,
  id,
  snapshot,
  version: 2,
  updatedAtMs: 1793923200000,
  deviceId: 'synthetic',
});
const diary = synthetic('diary_entries', 'demo-diary', {
  title: '合成示例：周末出行',
  content: '记录行程与午餐。',
  tags_json: '[]',
});

async function relationsCloud(page: Page) {
  const state = {
    signedIn: true,
    account: 'a',
    expire: false,
    generation: 'g1',
    failSearch: false,
    pageSize: 20,
    requests: [] as URL[],
    writes: [] as string[],
    rows: [
      diary,
      synthetic('diary_links', 'link-bill', {
        diary_id: diary.id,
        source_type: 'bill',
        source_id: 'bill-1',
        source_title: '午餐（关联时）',
        title: '午餐关联',
        snapshot_json: '{"title":"快照内的标题","bill_id":"fake-bill"}',
      }),
      synthetic('diary_links', 'link-track', {
        diary_id: diary.id,
        source_type: 'track',
        source_id: 'gone-track',
        source_title: '已删除的行程',
        title: '行程关联',
      }),
      synthetic('diary_links', 'unrelated', {
        diary_id: 'demo-diary-other',
        source_type: 'bill',
        source_id: 'unrelated-bill',
        title: '不应出现在图中的记录',
        note: diary.id,
      }),
      synthetic('media_attachments', 'attachment', {
        owner_type: 'diary',
        owner_id: diary.id,
        asset_id: 'image',
        title: '午餐照片',
      }),
      synthetic('media_assets', 'image', { title: '照片素材', mime_type: 'image/jpeg' }),
      synthetic('bills', 'bill-1', {
        title: '当前午餐账单',
        amount_cents: 3200,
        stored_value_account_id: 'wallet',
      }),
      synthetic('stored_value_accounts', 'wallet', { name: '合成储值账户' }),
      synthetic('food_entries', 'food', {
        title: '合成午餐',
        bill_id: 'bill-1',
        bill_ids_json: '["bill-1"]',
      }),
    ],
  };
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const send = (data: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify(apiFields(data, false)),
      });
    if (request.method() !== 'GET' && !url.pathname.startsWith('/api/sessions'))
      state.writes.push(url.pathname);
    if (url.pathname === '/api/sessions') {
      state.signedIn = true;
      state.account = request.postDataJSON().username === 'bob' ? 'b' : 'a';
      return send({
        accountId: state.account,
        userId: 'user',
        username: '合成示例',
        access: 'read_only',
      });
    }
    if (url.pathname === '/api/sessions/current') {
      state.signedIn = false;
      return send({ ok: true });
    }
    if (!state.signedIn || state.expire) return send({ error: 'unauthorized' }, 401);
    if (url.pathname === '/api/users/me')
      return send({ accountId: state.account, userId: 'user', username: '合成示例' });
    if (url.pathname === '/api/sync/capabilities')
      return send({
        accountId: state.account,
        dataset: { generation: state.generation },
        features: ['change_history_v1'],
      });
    if (url.pathname === '/api/statistics')
      return send({
        recordCounts: {},
        billExpenseCents: 0,
        billIncomeCents: 0,
        billStoredValueUseCents: 0,
      });
    if (url.pathname === '/api/change-events') {
      state.requests.push(url);
      return send({ events: [], generation: state.generation, more: false, nextCursor: '' });
    }
    const segments = url.pathname.split('/');
    const table = segments[2]?.replaceAll('-', '_');
    const rows =
      state.account === 'a'
        ? state.rows
        : [synthetic('diary_entries', diary.id, { title: 'Bob 的合成记录' })];
    if (segments.length === 4) {
      const found = rows.find(
        (row) => row.table === table && row.id === decodeURIComponent(segments[3]),
      );
      return found ? send(found) : send({ error: 'record not found' }, 404);
    }
    if (segments.length === 3) {
      state.requests.push(url);
      if (state.failSearch) {
        state.failSearch = false;
        return send({ error: 'query failed' }, 503);
      }
      const candidates = rows
        .filter(
          (row) =>
            row.table === table && JSON.stringify(row).includes(url.searchParams.get('q') ?? ''),
        )
        .sort((a, b) => a.id.localeCompare(b.id));
      const offset = Number(url.searchParams.get('cursor') ?? 0);
      const limit = Math.min(state.pageSize, Number(url.searchParams.get('limit') ?? 20));
      const pageRows = candidates.slice(offset, offset + limit);
      const more = offset + limit < candidates.length;
      return send({ rows: pageRows, more, nextCursor: more ? String(offset + limit) : '' });
    }
    return send({ error: 'unexpected path' }, 404);
  });
  return state;
}

const selectRecord = (page: Page, name: string) =>
  page.locator('.relation-records').getByRole('button').filter({ hasText: name }).click();
const selected = (page: Page) => page.getByRole('complementary', { name: '选中记录' });

test('opens real relations from details and supports keyboard expansion and record/history navigation', async ({
  page,
}, testInfo) => {
  const state = await relationsCloud(page);
  await page.goto(appPath('/records/diary_entries/demo-diary'));
  await page.getByRole('link', { name: '记录关系', exact: true }).click();
  await expect(page.getByRole('heading', { name: '记录关系', exact: true })).toBeVisible();
  await expect(selected(page).getByRole('button', { name: '已全部展开' })).toBeVisible();
  await expect(page.getByText('已加载 4 条记录 · 3 条关联')).toBeVisible();
  await page.screenshot({
    path: `.impeccable/review/relations-${testInfo.project.name}-light.png`,
    fullPage: true,
  });
  await page.getByRole('button', { name: '关联列表', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('.relation-records')).toBeVisible();
  await expect(page.getByText('不应出现在图中的记录')).toHaveCount(0);
  const link = page
    .locator('.relation-records')
    .getByRole('button')
    .filter({ hasText: '午餐关联' });
  await link.focus();
  await page.keyboard.press('Enter');
  await selected(page).getByRole('button', { name: '展开关联', exact: true }).click();
  await expect(selected(page).getByRole('button', { name: '已全部展开' })).toBeVisible();
  await selectRecord(page, '当前午餐账单');
  await expect(selected(page).getByText('保存的名称: 午餐（关联时）')).toBeVisible();
  await expect(page.getByText('快照内的标题')).toHaveCount(0);
  await selected(page).getByRole('button', { name: '展开关联', exact: true }).click();
  await expect(page.locator('.relation-records').getByText('合成储值账户')).toBeVisible();
  await selectRecord(page, '合成储值账户');
  await expect(
    selected(page).getByText('已到展开层数上限。打开记录可继续查看其关联。'),
  ).toBeVisible();
  await selectRecord(page, '当前午餐账单');
  await selected(page).getByRole('link', { name: '查看记录', exact: true }).click();
  await expect(page).toHaveURL(/records\/bills\/bill-1$/);
  await page.getByRole('link', { name: '记录关系', exact: true }).click();
  await selected(page).getByRole('link', { name: '查看记录历史', exact: true }).click();
  await expect(page.getByLabel('记录 ID', { exact: true })).toHaveValue('bill-1');
  expect(state.writes).toEqual([]);
});

test('saved missing sources remain readable in dark English and narrow layouts', async ({
  page,
}, testInfo) => {
  await relationsCloud(page);
  await page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'reduce' });
  await page.goto(appPath('/records/diary_entries/demo-diary/relations'));
  await expect(selected(page).getByRole('button', { name: '已全部展开' })).toBeVisible();
  await page.getByRole('button', { name: '关联列表', exact: true }).click();
  await selectRecord(page, '行程关联');
  await selected(page).getByRole('button', { name: '展开关联', exact: true }).click();
  await selectRecord(page, '已删除的行程');
  await expect(selected(page).getByText('轨迹 · 已删除或不存在')).toBeVisible();
  await expect(
    selected(page).getByRole('button', { name: '展开关联', exact: true }),
  ).toBeDisabled();
  await expect(selected(page).getByRole('link', { name: '查看记录', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '语言', exact: true }).click();
  await page.getByRole('menuitemradio', { name: 'English', exact: true }).click();
  await page.getByRole('button', { name: 'Graph', exact: true }).click();
  await expect(page.locator('html')).toHaveClass('dark');
  await expect(page.getByRole('heading', { name: 'Record relations', exact: true })).toBeVisible();
  await page.locator('#main').evaluate((element) => element.scrollTo(0, 0));
  await page.screenshot({
    path: `.impeccable/review/relations-${testInfo.project.name}-dark-en.png`,
    fullPage: true,
  });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  expect(
    await page.locator('#main').evaluate((element) => element.scrollWidth <= element.clientWidth),
  ).toBe(true);
  const keys = await page.evaluate(() => Object.keys(localStorage));
  expect(
    keys.every((key) => ['recoow:preferences', 'recoow:theme', 'recoow:palette'].includes(key)),
  ).toBe(true);
});

test('failed discovery retries without losing the root and offline expansion is disabled', async ({
  page,
}, testInfo) => {
  const state = await relationsCloud(page);
  state.failSearch = true;
  await page.goto(appPath('/records/diary_entries/demo-diary/relations'));
  await expect(selected(page).getByRole('alert')).toContainText('请求失败（503）');
  await expect(page.getByText('已加载 1 条记录 · 0 条关联')).toBeVisible();
  await selected(page).getByRole('button', { name: '重试', exact: true }).click();
  await expect(page.getByText('已加载 4 条记录 · 3 条关联')).toBeVisible();
  await page.getByRole('button', { name: '关联列表', exact: true }).click();
  await selectRecord(page, '午餐关联');
  await page.evaluate(() => window.dispatchEvent(new Event('offline')));
  await expect(
    selected(page).getByRole('button', { name: '展开关联', exact: true }),
  ).toBeDisabled();
  await page.screenshot({
    path: `.impeccable/review/relations-${testInfo.project.name}-offline-list.png`,
    fullPage: true,
  });
});

test('large tracks stop at the node limit while preserving pagination', async ({ page }) => {
  const state = await relationsCloud(page);
  state.rows = [
    synthetic('tracks', 'large-track', { title: '合成示例：长轨迹' }),
    ...Array.from({ length: 85 }, (_, index) =>
      synthetic('track_points', 'point-' + String(index).padStart(3, '0'), {
        track_id: 'large-track',
        timestamp_ms: 1793923200000 + index,
      }),
    ),
  ];
  await page.goto(appPath('/records/tracks/large-track/relations'));
  const loadMore = selected(page).getByRole('button', { name: '继续加载关联', exact: true });
  await expect(loadMore).toBeEnabled();
  await loadMore.click();
  await expect(loadMore).toBeEnabled();
  await loadMore.click();
  await expect(page.getByText('已加载 60 条记录 · 59 条关联')).toBeVisible();
  await expect(loadMore).toBeDisabled();
  await expect(
    selected(page).getByText('已加载 60 条记录。打开记录可继续查看其关联。'),
  ).toBeVisible();
  expect(state.requests.some((url) => url.searchParams.has('cursor'))).toBe(true);
  expect(state.writes).toEqual([]);
});

test('expired sessions and account changes clear the entire explored graph', async ({ page }) => {
  const state = await relationsCloud(page);
  await page.goto(appPath('/records/diary_entries/demo-diary/relations'));
  await expect(page.getByText('已加载 4 条记录 · 3 条关联')).toBeVisible();
  state.expire = true;
  await page.getByRole('button', { name: '刷新云端数据' }).click();
  await expect(page.getByText('登录已过期，请重新登录')).toBeVisible();
  await expect(page.getByRole('heading', { name: '记录关系', exact: true })).toHaveCount(0);
  state.expire = false;
  await page.getByLabel('用户名', { exact: true }).fill('bob');
  await page.getByLabel('密码', { exact: true }).fill('synthetic');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(selected(page).getByRole('heading', { name: 'Bob 的合成记录' })).toBeVisible();
  await expect(selected(page).getByText('这条记录没有关联记录。')).toBeVisible();
  await expect(page.getByText('午餐关联', { exact: true })).toHaveCount(0);
  await expect(page.getByText('已加载 1 条记录 · 0 条关联')).toBeVisible();
});

test('dataset generation changes discard explored relations and reload the current root', async ({
  page,
}) => {
  const state = await relationsCloud(page);
  await page.goto(appPath('/records/diary_entries/demo-diary/relations'));
  await expect(page.getByText('已加载 4 条记录 · 3 条关联')).toBeVisible();
  state.generation = 'g2';
  state.rows = [synthetic('diary_entries', diary.id, { title: '新世代的合成记录' })];
  await page.getByRole('button', { name: '刷新云端数据' }).click();
  await expect(selected(page).getByRole('heading', { name: '新世代的合成记录' })).toBeVisible();
  await expect(page.getByText('已加载 1 条记录 · 0 条关联')).toBeVisible();
  await expect(page.getByText('午餐关联', { exact: true })).toHaveCount(0);
});
