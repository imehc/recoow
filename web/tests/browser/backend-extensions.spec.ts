import { randomUUID } from 'node:crypto';
import { test, expect } from '@playwright/test';
import { appPath } from './paths.js';

test('account-authorized batch organization reaches real records and bill statistics drilldown', async ({
  page,
  request,
}) => {
  test.skip(
    !process.env.RECOOW_TEST_WEB_WRITES,
    'Requires an isolated backend with account Web access support.',
  );
  const registration = await request.post('/api/accounts', {
    data: { bootstrap_token: process.env.RECOOW_TEST_BOOTSTRAP_TOKEN ?? '' },
  });
  expect(registration.status()).toBe(201);
  const account = await registration.json(),
    headers = { Authorization: 'Bearer ' + account.device_token };
  const username = 'web-bulk-' + randomUUID(),
    password = randomUUID().slice(0, 18),
    now = Date.now();
  expect(
    (
      await request.post('/api/users/me/credentials', { headers, data: { username, password } })
    ).status(),
  ).toBe(201);
  expect(
    (
      await request.put('/api/accounts/current/web-access', { headers, data: { enabled: true } })
    ).status(),
  ).toBe(200);
  const create = async (table: string, snapshot: Record<string, unknown>) => {
    const id = randomUUID();
    const response = await request.post('/api/' + table.replaceAll('_', '-'), {
      headers,
      data: {
        mutation_id: randomUUID(),
        base_version: 0,
        data_version: 1,
        updated_at_ms: now,
        snapshot: {
          id,
          created_at: now,
          updated_at: now,
          device_id: account.device_id,
          ...snapshot,
        },
      },
    });
    expect(response.status(), await response.text()).toBe(201);
    return id;
  };
  const tag = await create('diary_tags', { name: '合成联调标签' });
  const diaries = [];
  for (const title of ['合成整理一', '合成整理二'])
    diaries.push(
      await create('diary_entries', {
        title,
        content: '合成正文',
        mood: 'neutral',
        occurred_at: now,
        tags_json: '[]',
        future: { retained: true },
      }),
    );
  const item = await create('stored_items', {
    title: '合成物品',
    location: '原位置',
    category_id: null,
  });
  const bill = await create('bills', {
    title: '合成支出',
    original_amount_cents: 1234,
    discount_amount_cents: 0,
    final_amount_cents: 1234,
    transaction_type: 'expense',
    category: 'food',
    payment_method: 'other',
    occurred_at: now,
    settlement_status: 'active',
  });
  await page.goto(appPath('/records/diary_entries'));
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('checkbox', { name: '选择已加载记录，最多 50 条' }).check();
  await page.getByRole('button', { name: '批量整理', exact: true }).click();
  await page.getByLabel('已有标签', { exact: true }).click();
  await page.getByRole('option', { name: '合成联调标签 · ' + tag, exact: true }).click();
  await page.getByRole('button', { name: '预览变更', exact: true }).click();
  await page.getByRole('button', { name: '确认并保存变更' }).click();
  await expect(page.getByRole('region', { name: '批量整理' })).toContainText('已保存: 2');
  for (const id of diaries) {
    const row = await (await request.get('/api/diary-entries/' + id, { headers })).json();
    expect(JSON.parse(row.snapshot.tags_json)).toEqual([{ key: tag, value: '合成联调标签' }]);
    expect(row.snapshot.future).toEqual({ retained: true });
  }
  await page.getByRole('button', { name: '关闭', exact: true }).click();
  await page.getByRole('checkbox', { name: '选择已加载记录，最多 50 条' }).check();
  await page.getByRole('button', { name: '批量整理', exact: true }).click();
  await page.getByLabel('整理方式', { exact: true }).click();
  await page.getByRole('option', { name: '移除标签', exact: true }).click();
  await page.getByLabel('要移除的标签', { exact: true }).click();
  await page.getByRole('option', { name: '合成联调标签 · ' + tag, exact: true }).click();
  await page.getByRole('button', { name: '预览变更', exact: true }).click();
  await page.getByRole('button', { name: '确认并保存变更' }).click();
  await expect(page.getByRole('region', { name: '批量整理' })).toContainText('已保存: 2');
  await page.getByRole('button', { name: '关闭', exact: true }).click();
  for (const table of ['stored_items', 'bills']) {
    await page.goto(appPath('/records/' + table));
    await page.getByRole('checkbox', { name: '选择已加载记录，最多 50 条' }).check();
    await page.getByRole('button', { name: '批量整理', exact: true }).click();
    if (table === 'stored_items') await page.getByLabel('位置', { exact: true }).fill('新位置');
    else {
      await page.getByLabel('分类', { exact: true }).click();
      await page.getByRole('option', { name: '其他', exact: true }).click();
    }
    await page.getByRole('button', { name: '预览变更', exact: true }).click();
    await page.getByRole('button', { name: '确认并保存变更' }).click();
    await expect(page.getByRole('region', { name: '批量整理' })).toContainText('已保存: 1');
    await page.getByRole('button', { name: '关闭', exact: true }).click();
  }
  expect(
    (await (await request.get('/api/stored-items/' + item, { headers })).json()).snapshot.location,
  ).toBe('新位置');
  expect(
    (await (await request.get('/api/bills/' + bill, { headers })).json()).snapshot.category,
  ).toBe('other');
  await page.goto(appPath('/statistics'));
  const category = page.getByRole('link', { name: '其他', exact: true });
  await expect(category).toBeVisible();
  await category.click();
  await expect(page.getByRole('link', { name: '合成支出', exact: true })).toBeVisible();
  const url = new URL(page.url());
  expect(url.searchParams.get('category')).toBe('other');
  expect(url.searchParams.get('exclude_refunded')).toBe('true');
  const stats = await page.request.get(
    '/api/statistics/bills?year=' + new Date().getFullYear() + '&timezone=Asia%2FShanghai',
  );
  expect(stats.status()).toBe(200);
  const totals = await stats.json();
  expect(totals.categories).toEqual([{ category: 'other', expense_cents: 1234, count: 1 }]);
});
