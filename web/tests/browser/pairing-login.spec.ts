import { test, expect } from '@playwright/test';
import { appPath } from './paths.js';

test('pairing login preserves leading zeros, rejects reused codes and exits after device revocation', async ({
  page,
}) => {
  await page.clock.install();
  let authorized = false,
    revoked = false;
  const attempts: Record<string, unknown>[] = [];
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    const send = (body: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/pairing-sessions') {
      const body = route.request().postDataJSON();
      attempts.push(body);
      if (body.code !== '0123456789') return send({ error: 'invalid code' }, 401);
      authorized = true;
      return send({
        user_id: '',
        username: '',
        account_id: 'paired',
        device_id: 'web',
        access_token: 'short-lived',
        expires_at: '2099-01-01T00:00:00Z',
        access: 'read_only',
      });
    }
    if (!authorized || revoked) return send({ error: 'revoked' }, 401);
    if (path === '/api/users/me')
      return send({
        user_id: '',
        username: '',
        account_id: 'paired',
        access: 'read_only',
        writable_tables: [],
      });
    if (path === '/api/sync/capabilities')
      return send({ account_id: 'paired', dataset: { generation: 'g' }, features: [] });
    if (path === '/api/statistics')
      return send({
        record_counts: {},
        bill_expense_cents: 0,
        bill_income_cents: 0,
        bill_stored_value_use_cents: 0,
      });
    return send({ rows: [], more: false });
  });
  await page.goto(appPath('/'));
  await page.getByRole('tab', { name: '配对码登录', exact: true }).click();
  await page.getByLabel('配对码', { exact: true }).fill('123');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  expect(attempts).toHaveLength(0);
  await page.getByLabel('配对码', { exact: true }).fill('0000000000');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect.poll(() => attempts.length).toBe(1);
  await expect(page.getByRole('alert')).toContainText('配对码无效');
  await page.getByLabel('配对码', { exact: true }).fill('0123456789');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '记录概览', exact: true })).toBeVisible();
  expect(attempts.at(-1)).toEqual({ code: '0123456789' });
  await expect(page.getByRole('button', { name: '账号菜单', exact: true })).toContainText(
    '配对账号',
  );
  revoked = true;
  await page.clock.fastForward(15_000);
  await expect(page.getByRole('tab', { name: '配对码登录', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '记录概览', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '账号菜单', exact: true })).toHaveCount(0);
  await page.getByRole('tab', { name: '配对码登录', exact: true }).click();
  await expect(page.getByLabel('配对码', { exact: true })).toHaveValue('');
});
