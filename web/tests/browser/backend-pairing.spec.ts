import { test, expect } from '@playwright/test';
import { appPath } from './paths.js';

test('primary-device pairing signs into an unbound account and revocation rejects Cookie reads and writes', async ({
  page,
  request,
}) => {
  test.skip(
    !process.env.RECOOW_TEST_BACKEND,
    'Requires an isolated backend with pairing sessions.',
  );
  const created = await request.post('/api/accounts', {
    data: { bootstrap_token: process.env.RECOOW_TEST_BOOTSTRAP_TOKEN ?? '' },
  });
  expect(created.status()).toBe(201);
  const account = await created.json(),
    headers = { Authorization: 'Bearer ' + account.device_token };
  const issue = await request.post('/api/device-pairing-codes', { headers });
  expect(issue.status()).toBe(200);
  const { code } = await issue.json();
  await page.goto(appPath('/'));
  await page.getByRole('tab', { name: '配对码登录', exact: true }).click();
  await page.getByLabel('配对码', { exact: true }).fill(code);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '记录概览', exact: true })).toBeVisible();
  const me = await page.request.get('/api/users/me');
  expect(me.status()).toBe(200);
  expect((await me.json()).account_id).toBe(account.account_id);
  expect((await page.request.post('/api/pairing-sessions', { data: { code } })).status()).toBe(401);
  expect(
    (
      await request.put('/api/accounts/current/web-access', { headers, data: { enabled: true } })
    ).status(),
  ).toBe(200);
  await page.getByRole('button', { name: '刷新云端数据', exact: true }).click();
  await page.goto(appPath('/records/diary_tags'));
  await expect(page.getByRole('link', { name: '新增记录', exact: true })).toBeVisible();
  const list = await request.get('/api/devices', { headers });
  const devices = (await list.json()).devices;
  const device = devices.find((d: { name: string }) => d.name === 'Web 配对会话');
  expect(device).toBeTruthy();
  expect((await request.delete('/api/devices/' + device.id, { headers })).status()).toBe(200);
  for (const path of ['/api/users/me', '/api/statistics', '/api/diary-tags'])
    expect((await page.request.get(path)).status()).toBe(401);
  expect(
    (
      await page.request.post('/api/diary-tags', {
        headers: { Origin: new URL(page.url()).origin },
        data: {},
      })
    ).status(),
  ).toBe(401);
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page.getByRole('tab', { name: '配对码登录', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '新增记录', exact: true })).toHaveCount(0);
  expect((await request.get('/api/devices', { headers })).status()).toBe(200);
});
