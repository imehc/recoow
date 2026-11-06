import { randomUUID } from 'node:crypto';
import { test, expect } from '@playwright/test';

test('cloud cookies, offline timestamps and read-only permissions work against Go', async ({
  page,
  request,
}) => {
  test.skip(
    !process.env.RECOOW_TEST_BACKEND,
    'Requires an isolated backend with account registration enabled.',
  );
  const username = `web-${randomUUID()}`;
  const password = randomUUID();
  const deviceHeaders = {
    'X-Recoow-Client': 'ios',
    'X-Recoow-OS': 'ios',
    'X-Recoow-Device-Id': randomUUID(),
  };
  const registered = await request.post('/api/accounts', {
    headers: deviceHeaders,
    data: { bootstrapToken: process.env.RECOOW_TEST_BOOTSTRAP_TOKEN ?? '' },
  });
  expect(registered.status()).toBe(201);
  const account = await registered.json();
  const headers = { ...deviceHeaders, Authorization: `Bearer ${account.deviceToken}` };
  expect(
    (
      await request.post('/api/users/me/credentials', { headers, data: { username, password } })
    ).status(),
  ).toBe(201);
  const id = randomUUID();
  const editedAt = Date.now() - 86400000;
  const snapshot = {
    id,
    created_at: editedAt,
    updated_at: editedAt,
    device_id: deviceHeaders['X-Recoow-Device-Id'],
    title: '真实云端离线记录',
    content: '同步前的内容',
    mood: '',
    tags_json: '[]',
    occurred_at: editedAt,
  };
  const created = await request.post('/api/diary-entries', {
    headers,
    data: {
      mutationId: randomUUID(),
      baseVersion: 0,
      dataVersion: 1,
      updatedAtMs: editedAt,
      snapshot,
    },
  });
  expect(created.status(), await created.text()).toBe(201);
  expect((await created.json()).status).toBe('applied');

  await page.goto('/records/diary_entries');
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('link', { name: '真实云端离线记录', exact: true })).toBeVisible();
  const cookies = await page.context().cookies();
  expect(cookies.find((cookie) => cookie.name === 'recoow_session')).toMatchObject({
    httpOnly: true,
    sameSite: 'Strict',
    path: '/api',
  });
  const writeStatus = await page.evaluate(
    async () =>
      (
        await fetch('/api/diary-entries/forbidden', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json', 'X-Recoow-Client': 'ios' },
          body: '{}',
        })
      ).status,
  );
  expect(writeStatus).toBe(403);
  await page.getByRole('link', { name: '查看记录', exact: true }).click();
  await page.getByRole('link', { name: '查看这条记录的所有变化 →' }).click();
  await page.getByRole('link', { name: '查看变化', exact: true }).click();
  await expect(page.getByRole('heading', { name: '时间与来源' })).toBeVisible();
  const times = await page.locator('.field').allTextContents();
  expect(times.find((text) => text.includes('客户端修改时间'))).not.toBe(
    times.find((text) => text.includes('服务端接受时间')),
  );
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page.getByRole('heading', { name: '查看你的云端记录' })).toBeVisible();
  expect(
    (await page.context().cookies()).filter((cookie) => cookie.name === 'recoow_session'),
  ).toEqual([]);
});
