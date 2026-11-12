import { test, expect, type Page } from '@playwright/test';
import { stringify } from 'lossless-json';
import { apiFields } from '../../src/lib/api-fields.js';
import { appPath } from './paths.js';
import { mkdir } from 'node:fs/promises';

async function capture(page: Page, name: string, project: string) {
  if (!process.env.RECOOW_CAPTURE_EXTENSIONS) return;
  await mkdir('.impeccable/review/extensions', { recursive: true });
  await page.screenshot({
    path: `.impeccable/review/extensions/${project}-${name}.png`,
    fullPage: false,
  });
}

async function cloud(page: Page) {
  const state = {
    access: 'read_write',
    lost: true,
    wrongReceipt: false,
    writes: [] as string[],
    reads: [] as URL[],
    rows: [
      {
        table: 'diary_entries',
        id: 'a',
        version: 2,
        dataVersion: 1,
        deviceId: 'd',
        updatedAtMs: 100,
        snapshot: { id: 'a', title: '合成日记一', content: '正文', tags_json: '[]', future: true },
      },
      {
        table: 'diary_entries',
        id: 'b',
        version: 3,
        dataVersion: 1,
        deviceId: 'd',
        updatedAtMs: 100,
        snapshot: { id: 'b', title: '合成日记二', content: '正文', tags_json: '[]' },
      },
      {
        table: 'diary_entries',
        id: 'c',
        version: 4,
        dataVersion: 1,
        deviceId: 'd',
        updatedAtMs: 100,
        snapshot: { id: 'c', title: '合成日记三', content: '正文', tags_json: '[]' },
      },
      {
        table: 'stored_items',
        id: 'item',
        version: 1,
        dataVersion: 1,
        deviceId: 'd',
        updatedAtMs: 100,
        snapshot: { id: 'item', title: '合成盒子', location: '书房二层', category_id: 'cat' },
      },
      {
        table: 'item_categories',
        id: 'cat',
        version: 1,
        dataVersion: 1,
        deviceId: 'd',
        updatedAtMs: 100,
        snapshot: { id: 'cat', name: '合成收纳分类' },
      },
      {
        table: 'bills',
        id: 'bill',
        version: 1,
        dataVersion: 1,
        deviceId: 'd',
        updatedAtMs: 100,
        snapshot: {
          id: 'bill',
          title: '合成早餐',
          transaction_type: 'expense',
          category: 'food',
          final_amount_cents: 1234,
          occurred_at: 1790000000123,
        },
      },
    ] as Record<string, unknown>[],
  };
  const receipts = new Map<string, unknown>();
  for (const row of state.rows) {
    row.snapshot = {
      occurred_at: 100,
      mood: 'neutral',
      category: 'other',
      transaction_type: 'expense',
      payment_method: 'other',
      original_amount_cents: 1234,
      discount_amount_cents: 0,
      final_amount_cents: 1234,
      settlement_status: 'active',
      ...(row.snapshot as Record<string, unknown>),
    };
  }
  await page.route('**/api/**', async (route) => {
    const request = route.request(),
      url = new URL(request.url());
    const send = (value: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: 'application/json',
        body: stringify(apiFields(value, false)) ?? '{}',
      });
    if (url.pathname === '/api/users/me')
      return send({
        accountId: 'a',
        userId: 'u',
        username: '合成示例',
        access: state.access,
        writableTables:
          state.access === 'read_write'
            ? ['bills', 'diary_entries', 'stored_items', 'diary_tags']
            : [],
      });
    if (url.pathname === '/api/sync/capabilities')
      return send({
        accountId: 'a',
        dataset: { generation: 'g' },
        features: ['change_history_v1'],
      });
    if (url.pathname === '/api/change-events')
      return send({ events: [], more: false, generation: 'g' });
    if (url.pathname === '/api/statistics/bills') {
      const year = Number(url.searchParams.get('year')),
        start = new Date(`${year}-01-01T00:00:00+08:00`).getTime();
      const months = Array.from({ length: 12 }, (_, i) => ({
        month: i + 1,
        fromMs: new Date(year, i, 1).getTime(),
        toMs: new Date(year, i + 1, 1).getTime(),
        expenseCents: i === 0 ? 1234 : 0,
        incomeCents: 0,
        storedValueUseCents: 0,
      }));
      return send({
        year,
        timezone: 'Asia/Shanghai',
        months,
        previousMonths: months.map((m) => ({
          ...m,
          fromMs: m.fromMs - 31536000000,
          toMs: m.toMs - 31536000000,
          expenseCents: 0,
        })),
        categories: [{ category: '', expenseCents: 1234, count: 1 }],
        start,
      });
    }
    const table = url.pathname.split('/')[2].replaceAll('-', '_'),
      id = url.pathname.split('/')[3];
    if (request.method() === 'GET') {
      state.reads.push(url);
      if (id) {
        const row = state.rows.find((r) => r.table === table && r.id === id);
        return row ? send(row) : send({ error: 'missing' }, 404);
      }
      return send({
        rows: state.rows.filter((r) => r.table === table),
        more: false,
        nextCursor: '',
      });
    }
    const body = request.postData()!;
    state.writes.push(body);
    const payload = JSON.parse(body);
    if (receipts.has(payload.mutation_id)) return send(receipts.get(payload.mutation_id));
    if (id === 'b')
      return send({ id, table, status: 'conflict', applied: false, current: state.rows[1] }, 409);
    if (id === 'c') return send({ error: 'invalid' }, 400);
    const result = {
      id: state.wrongReceipt ? 'wrong' : id,
      table,
      status: 'applied',
      applied: true,
      version: 9,
    };
    receipts.set(payload.mutation_id, result);
    if (state.lost) {
      state.lost = false;
      return route.abort('failed');
    }
    return send(result);
  });
  return state;
}

test('bulk preview freezes inputs, retries the same receipt and summarizes individual results', async ({
  page,
}, info) => {
  const state = await cloud(page);
  await page.goto(appPath('/records/diary_entries'));
  await page.getByRole('checkbox', { name: '选择已加载记录，最多 50 条' }).check();
  await page.getByRole('button', { name: '批量整理', exact: true }).click();
  await page.getByLabel('或输入新标签名称').fill('合成批量标签');
  await page.getByRole('button', { name: '预览变更', exact: true }).click();
  const panel = page.getByRole('region', { name: '批量整理', exact: true });
  await expect(panel).toContainText('待保存: 3');
  await expect(panel).toContainText('合成批量标签');
  await expect(panel).toContainText('无标签');
  await expect(panel).not.toContainText('"key":');
  await panel.getByRole('heading', { name: '批量整理', exact: true }).scrollIntoViewIfNeeded();
  await capture(page, 'bulk-preview', info.project.name);
  await page.getByRole('button', { name: '刷新云端数据' }).click();
  await page.getByRole('button', { name: '确认并保存变更' }).click();
  await expect(panel).toContainText('回执未知: 1');
  expect(state.writes).toHaveLength(1);
  await panel.getByRole('button', { name: '重试', exact: true }).scrollIntoViewIfNeeded();
  await capture(page, 'bulk-unknown', info.project.name);
  await page.getByRole('button', { name: '重试', exact: true }).click();
  await expect(panel).toContainText('已保存: 1');
  await expect(panel).toContainText('待处理冲突: 1');
  await expect(panel).toContainText('保存失败: 1');
  await panel.getByText('请求失败（400）', { exact: true }).scrollIntoViewIfNeeded();
  await capture(page, 'bulk-results', info.project.name);
  await page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'reduce' });
  await capture(page, 'bulk-results-dark', info.project.name);
  expect(state.writes[0]).toBe(state.writes[1]);
  expect(JSON.parse(state.writes[0]).base_version).toBe(2);
  expect(JSON.parse(state.writes[0]).snapshot.future).toBe(true);
});

test('bulk navigation confirms unfinished previews and receipt identity is checked', async ({
  page,
}, info) => {
  const state = await cloud(page);
  state.lost = false;
  state.wrongReceipt = true;
  await page.goto(appPath('/records/stored_items'));
  await page.getByRole('checkbox', { name: '选择记录：合成盒子' }).check();
  await page.getByRole('button', { name: '批量整理', exact: true }).click();
  await page.getByLabel('位置', { exact: true }).fill('合成新位置');
  await page.getByRole('button', { name: '预览变更' }).click();
  await page.getByRole('link', { name: '合成盒子', exact: true }).first().click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await capture(page, 'bulk-leave', info.project.name);
  await page.getByRole('button', { name: '继续编辑', exact: true }).click();
  await page.getByRole('button', { name: '确认并保存变更' }).click();
  await expect(page.getByRole('region', { name: '批量整理' })).toContainText('回执未知: 1');
});

test('business lists resolve item categories and statistics drilldown retains exact filters', async ({
  page,
}, info) => {
  const state = await cloud(page);
  await page.goto(appPath('/records/stored_items'));
  await expect(page.getByRole('link', { name: '合成收纳分类' })).toBeVisible();
  await capture(page, 'items', info.project.name);
  await expect(page.getByText('书房二层', { exact: true })).toBeVisible();
  await page.goto(appPath('/statistics'));
  await expect(page.getByRole('heading', { name: '年度对比' })).toBeVisible();
  const currentYear = new Date().getFullYear();
  const yearField = page.getByRole('button', { name: '年份', exact: true });
  await yearField.click();
  await page.getByRole('button', { name: String(currentYear - 1), exact: true }).click();
  await expect(yearField).toHaveText(String(currentYear - 1));
  await expect(page.locator('.data-table').first().locator('th').nth(1)).toHaveText(
    String(currentYear),
  );
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.locator('.data-table').first().locator('th').nth(1)).toHaveText(
    String(currentYear - 1),
  );
  await page.getByRole('button', { name: '下一年', exact: true }).click();
  await expect(yearField).toHaveText(String(currentYear));
  await yearField.click();
  await page.getByRole('button', { name: '下一组年份', exact: true }).click();
  await expect(
    page.getByRole('button', { name: String(Math.floor(currentYear / 12) * 12 + 12), exact: true }),
  ).toBeVisible();
  await page.keyboard.press('Escape');
  await capture(page, 'statistics-top', info.project.name);
  await page.getByRole('heading', { name: '分类支出', exact: true }).scrollIntoViewIfNeeded();
  await capture(page, 'statistics-categories', info.project.name);
  await page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'reduce' });
  await page.getByRole('button', { name: '语言', exact: true }).click();
  await page.getByRole('menuitemradio', { name: 'English', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Expenses by category', exact: true }),
  ).toBeVisible();
  await capture(page, 'statistics-categories-dark-en', info.project.name);
  await page.locator('main').evaluate((element) => {
    element.scrollTop = 0;
  });
  await capture(page, 'statistics-top-dark-en', info.project.name);
  await page.getByRole('button', { name: 'Language', exact: true }).click();
  await page.getByRole('menuitemradio', { name: '简体中文', exact: true }).click();
  await page.emulateMedia({ colorScheme: 'light' });
  await page.getByRole('link', { name: '未分类', exact: true }).click();
  await expect(page.getByText('已排除退款', { exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '合成早餐', exact: true })).toBeVisible();
  await capture(page, 'bills', info.project.name);
  const request = state.reads.findLast((url) => url.pathname === '/api/bills')!;
  expect(request.searchParams.get('transaction_type')).toBe('expense');
  expect(request.searchParams.has('category')).toBe(true);
  expect(request.searchParams.get('category')).toBe('');
  expect(request.searchParams.get('exclude_refunded')).toBe('true');
  const crumbs = page.getByRole('navigation', { name: '工作台', exact: true });
  await crumbs.getByRole('link', { name: '记录', exact: true }).click();
  await expect(page).toHaveURL(appPath('/records'));
});

test('read-only sessions see primary-device guidance and no bulk controls', async ({
  page,
}, info) => {
  const state = await cloud(page);
  state.access = 'read';
  await page.goto(appPath('/records/bills'));
  await expect(page.getByRole('checkbox')).toHaveCount(0);
  await page.getByText('开启 Web 编辑', { exact: true }).click();
  await expect(page.getByText(/主设备打开/)).toBeVisible();
  await capture(page, 'access-guide', info.project.name);
});
