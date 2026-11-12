import { randomUUID } from 'node:crypto';
import { test, expect } from '@playwright/test';
import { appPath } from './paths.js';

test('authorized Web CRUD reaches Go history and mobile sync with versioned conflicts and receipt retries', async ({
  page,
  request,
}) => {
  test.skip(
    !process.env.RECOOW_TEST_WEB_WRITES,
    'Requires an isolated backend with account Web access support.',
  );
  const username = 'web-edit-' + randomUUID(),
    password = randomUUID().slice(0, 18);
  const registration = await request.post('/api/accounts', {
    data: { bootstrap_token: process.env.RECOOW_TEST_BOOTSTRAP_TOKEN ?? '' },
  });
  expect(registration.status()).toBe(201);
  const account = await registration.json();
  const headers = { Authorization: 'Bearer ' + account.device_token };
  expect(
    (
      await request.post('/api/users/me/credentials', { headers, data: { username, password } })
    ).status(),
  ).toBe(201);
  await page.goto(appPath('/records/diary_tags'));
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '日记标签', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '新增记录', exact: true })).toHaveCount(0);
  const cookieBefore = (await page.context().cookies()).find(
    (cookie) => cookie.name === 'recoow_session',
  )?.value;
  expect(
    (
      await request.put('/api/accounts/current/web-access', { headers, data: { enabled: true } })
    ).status(),
  ).toBe(200);
  await page.getByRole('button', { name: '刷新云端数据', exact: true }).click();
  await expect(page.getByRole('link', { name: '新增记录', exact: true })).toBeVisible();
  expect(
    (await page.context().cookies()).find((cookie) => cookie.name === 'recoow_session')?.value,
  ).toBe(cookieBefore);
  const ids: Record<string, string> = {};
  for (const table of ['bills', 'diary_entries', 'stored_items', 'diary_tags']) {
    await page.goto(appPath('/records/' + table + '/new'));
    await page
      .getByLabel(table === 'diary_tags' ? /^名称/ : /^标题/)
      .first()
      .fill('合成联调 ' + table);
    if (table === 'bills') {
      await page.getByLabel(/^原价/).fill('10.00');
      await page.getByLabel(/^优惠/).fill('2.00');
      await page.getByLabel(/^实付金额/).fill('8.00');
    }
    if (table === 'stored_items') await page.getByLabel(/^位置/).fill('合成书架');
    if (table === 'diary_entries') await page.getByLabel(/^内容/).fill('合成正文');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByRole('heading', { name: '已保存', exact: true })).toBeVisible();
    await page.getByRole('link', { name: '查看记录', exact: true }).click();
    await expect(page).toHaveURL(new RegExp('/records/' + table + '/[0-9a-f-]+$'));
    ids[table] = decodeURIComponent(new URL(page.url()).pathname.split('/').at(-1)!);
    const fetched = await request.get('/api/' + table.replaceAll('_', '-') + '/' + ids[table], {
      headers,
    });
    expect(fetched.status()).toBe(200);
    expect((await fetched.json()).snapshot[table === 'diary_tags' ? 'name' : 'title']).toBe(
      '合成联调 ' + table,
    );
  }
  const tagId = ids.diary_tags;
  const current = await (await request.get('/api/diary-tags/' + tagId, { headers })).json();
  await page.goto(appPath('/records/diary_tags/' + tagId + '/edit'));
  await page.getByLabel(/^名称/).fill('本次本地名称');
  const remote = await request.put('/api/diary-tags/' + tagId, {
    headers,
    data: {
      mutation_id: randomUUID(),
      base_version: current.version,
      data_version: 1,
      updated_at_ms: Date.now(),
      snapshot: { ...current.snapshot, name: '云端并发名称', note: '兼容备注' },
    },
  });
  expect(remote.status()).toBe(200);
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('heading', { name: '处理冲突', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '保留本次争议内容', exact: true }).click();
  await expect(page.getByRole('heading', { name: '已保存', exact: true })).toBeVisible();
  const resolved = await (await request.get('/api/diary-tags/' + tagId, { headers })).json();
  expect(resolved.snapshot).toMatchObject({ name: '本次本地名称', note: '兼容备注' });
  await page.goto(appPath('/records/diary_tags/' + tagId + '/edit'));
  await page.getByLabel(/^名称/).fill('回执重试名称');
  let lost = false;
  const bodies: string[] = [];
  await page.route('**/api/diary-tags/' + tagId, async (route) => {
    if (route.request().method() !== 'PUT') return route.continue();
    bodies.push(route.request().postData()!);
    const response = await route.fetch();
    if (!lost) {
      lost = true;
      return route.abort('failed');
    }
    return route.fulfill({ response });
  });
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('heading', { name: '提交结果待确认' })).toBeVisible();
  await page.getByRole('button', { name: '确认提交结果', exact: true }).click();
  await expect(page.getByRole('heading', { name: '已保存', exact: true })).toBeVisible();
  expect(bodies).toHaveLength(2);
  expect(bodies[0]).toBe(bodies[1]);
  await page.unroute('**/api/diary-tags/' + tagId);
  const history = await (
    await request.get('/api/change-events?table=diary_tags&id=' + tagId, { headers })
  ).json();
  expect(
    history.events.filter(
      (event: { submission?: { client?: string } }) => event.submission?.client === 'web',
    ),
  ).toHaveLength(3);
  const pull = await (await request.get('/api/sync/changes', { headers })).json();
  expect(
    pull.entries.some(
      (entry: { id: string; snapshot?: { name?: string } }) =>
        entry.id === tagId && entry.snapshot?.name === '回执重试名称',
    ),
  ).toBe(true);
  for (const table of Object.keys(ids)) {
    await page.goto(appPath('/records/' + table + '/' + ids[table] + '/edit'));
    await page.getByRole('button', { name: '删除记录', exact: true }).click();
    await page.getByRole('button', { name: '确认删除', exact: true }).click();
    await expect(page.getByRole('heading', { name: '记录已删除' })).toBeVisible();
  }
  const denied = await page.evaluate(
    async () =>
      (
        await fetch('/api/sync/mutations', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: '{}',
        })
      ).status,
  );
  expect(denied).toBe(403);
  expect(
    (
      await request.put('/api/accounts/current/web-access', { headers, data: { enabled: false } })
    ).status(),
  ).toBe(200);
  // 页面尚未刷新也无法提交，撤销由服务端强制执行。
  const revoked = await page.evaluate(
    async () =>
      (
        await fetch('/api/diary-tags', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: '{}',
        })
      ).status,
  );
  expect(revoked).toBe(403);
  await page.goto(appPath('/records/diary_tags'));
  await page.reload();
  await expect(page.getByRole('heading', { name: '日记标签', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '新增记录', exact: true })).toHaveCount(0);
});
