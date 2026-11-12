import { test, expect, type Page } from '@playwright/test';
import { stringify, parse } from 'lossless-json';
import { apiFields } from '../../src/lib/api-fields.js';
import { appPath } from './paths.js';
import type { Row } from '../../src/lib/generated/api/models/Row.js';

async function editingCloud(page: Page) {
  const initial = parse(
    '{"id":"tag","name":"合成标签","note":null,"created_at":1793923200123,"updated_at":1793923200123,"device_id":"device","future":{"amount":9007199254740993}}',
  ) as Record<string, unknown>;
  const state = {
    access: 'read_write',
    generation: 'g1',
    account: 'a',
    expire: false,
    loseResponse: false,
    conflicts: 0,
    writes: [] as { body: string; method: string; path: string }[],
    reads: [] as string[],
    version: 2,
    rows: [
      {
        table: 'diary_tags',
        id: 'tag',
        snapshot: initial,
        version: 2,
        dataVersion: 1,
        updatedAtMs: 1793923200123,
        deviceId: 'device',
      },
    ] as Row[],
  };
  const receipts = new Map<string, unknown>();
  await page.route('**/api/**', async (route) => {
    const request = route.request(),
      url = new URL(request.url());
    const send = (value: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: 'application/json',
        body: stringify(apiFields(value, false)) ?? '{}',
      });
    if (state.expire) return send({ error: 'unauthorized' }, 401);
    if (url.pathname === '/api/users/me')
      return send({
        accountId: state.account,
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
        accountId: state.account,
        dataset: { generation: state.generation },
        features: ['change_history_v1'],
      });
    if (url.pathname === '/api/statistics')
      return send({
        recordCounts: { diary_tags: 1 },
        billExpenseCents: 0,
        billIncomeCents: 0,
        billStoredValueUseCents: 0,
      });
    if (url.pathname === '/api/change-events') {
      state.reads.push('history');
      return send({ events: [], more: false, generation: state.generation });
    }
    const parts = url.pathname.split('/'),
      table = parts[2]?.replaceAll('-', '_'),
      id = decodeURIComponent(parts[3] ?? '');
    if (request.method() === 'GET') {
      state.reads.push(url.pathname);
      if (parts.length === 4) {
        const row = state.rows.find((row) => row.table === table && row.id === id);
        return row ? send(row) : send({ error: 'not found' }, 404);
      }
      const rows = state.rows.filter((row) => row.table === table && !row.deletedAtMs);
      return send({ rows, more: false, nextCursor: '' });
    }
    const body = request.postData() ?? '{}';
    state.writes.push({ body, method: request.method(), path: url.pathname });
    const mutation = parse(body) as {
      mutation_id: string;
      generation: string;
      snapshot?: Record<string, unknown>;
      base_version: number;
      expected_version?: number;
      resolution?: string;
    };
    const recordId = id || String(mutation.snapshot?.id);
    if (mutation.generation !== state.generation) return send({ error: 'reset_required' }, 409);
    if (receipts.has(mutation.mutation_id)) return send(receipts.get(mutation.mutation_id));
    if (state.conflicts > 0) {
      state.conflicts--;
      const current: Row = {
        ...state.rows[0],
        version: ++state.version,
        snapshot: {
          ...state.rows[0].snapshot,
          name: '云端争议名称 ' + state.version,
          note: '云端兼容备注',
        },
      };
      state.rows[0] = current;
      const receipt = {
        table,
        id: recordId,
        mutationId: mutation.mutation_id,
        status: 'conflict',
        applied: false,
        version: current.version,
        current,
      };
      receipts.set(mutation.mutation_id, receipt);
      return send(receipt, 409);
    }
    const row: Row = {
      table,
      id: recordId,
      version: ++state.version,
      dataVersion: 1,
      updatedAtMs: Date.now(),
      deviceId: 'web',
      snapshot: mutation.snapshot ?? state.rows.find((row) => row.id === recordId)?.snapshot,
      deletedAtMs: request.method() === 'DELETE' ? Date.now() : 0,
    };
    state.rows = state.rows.filter((old) => old.id !== recordId);
    state.rows.push(row);
    const receipt = {
      table,
      id: recordId,
      mutationId: mutation.mutation_id,
      status: 'applied',
      applied: true,
      version: row.version,
      current: row,
    };
    receipts.set(mutation.mutation_id, receipt);
    if (state.loseResponse) {
      state.loseResponse = false;
      return route.abort('failed');
    }
    return send(receipt, request.method() === 'POST' ? 201 : 200);
  });
  return state;
}

test('only authorized sessions show edit entries', async ({ page }) => {
  const state = await editingCloud(page);
  state.access = 'read_only';
  await page.goto(appPath('/records/diary_tags/tag'));
  await expect(page.getByRole('heading', { name: '合成标签' })).toBeVisible();
  await expect(page.getByRole('link', { name: '编辑记录' })).toHaveCount(0);
  await page.goto(appPath('/records/diary_tags/new'));
  await expect(page.getByRole('alert')).toContainText('会话');
  expect(state.writes).toEqual([]);
});

test('refresh applies account access changes to the existing session', async ({ page }) => {
  const state = await editingCloud(page);
  state.access = 'read_only';
  await page.goto(appPath('/records/diary_tags/tag'));
  await expect(page.getByRole('heading', { name: '合成标签' })).toBeVisible();
  await expect(page.getByRole('link', { name: '编辑记录', exact: true })).toHaveCount(0);
  state.access = 'read_write';
  await page.getByRole('button', { name: '刷新云端数据', exact: true }).click();
  await expect(page.getByRole('link', { name: '编辑记录', exact: true })).toBeVisible();
  state.access = 'read_only';
  await page.reload();
  await expect(page.getByRole('heading', { name: '合成标签' })).toBeVisible();
  await expect(page.getByRole('link', { name: '编辑记录', exact: true })).toHaveCount(0);
  expect(state.writes).toEqual([]);
});

test('creates all four core records with exact amounts and mobile tag objects', async ({
  page,
}, info) => {
  const state = await editingCloud(page);
  for (const table of ['bills', 'diary_entries', 'stored_items', 'diary_tags']) {
    await page.goto(appPath('/records/' + table + '/new'));
    await page
      .getByLabel(table === 'diary_tags' ? '名称' : '标题', { exact: false })
      .first()
      .fill('合成新增 ' + table);
    if (table === 'bills') {
      await page.getByLabel('原价', { exact: false }).fill('90071992547409.93');
      await page.getByLabel('优惠', { exact: false }).fill('0.00');
      await page.getByLabel('实付金额', { exact: false }).fill('90071992547409.93');
    }
    if (table === 'stored_items') await page.getByLabel('位置', { exact: false }).fill('书架');
    if (table === 'diary_entries') {
      await page.getByLabel('内容', { exact: false }).fill('合成正文\n第二行');
      await page.getByLabel('标签', { exact: true }).fill('合成标签');
      await page.getByRole('button', { name: '添加标签', exact: true }).click();
      await expect(page.getByLabel('内容', { exact: false })).toHaveCSS('border-top-width', '1px');
      await page.evaluate(() => {
        (document.activeElement as HTMLElement)?.blur();
        window.scrollTo(0, 0);
      });
      await page.locator('#main').evaluate((element) => element.scrollTo(0, 0));
      await page.screenshot({
        path: `.impeccable/review/editing-${info.project.name}-light.png`,
        fullPage: false,
      });
      await page.locator('#main').evaluate((element) => element.scrollTo(0, element.scrollHeight));
      await page.screenshot({
        path: `.impeccable/review/editing-${info.project.name}-light-details.png`,
        fullPage: false,
      });
    }
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByRole('heading', { name: '已保存', exact: true })).toBeVisible();
  }
  const bill = state.writes.find((write) => write.path === '/api/bills')!;
  expect(bill.body).toContain('9007199254740993');
  const diary = JSON.parse(state.writes.find((write) => write.path === '/api/diary-entries')!.body);
  expect(JSON.parse(diary.snapshot.tags_json)[0]).toMatchObject({
    key: expect.any(String),
    value: '合成标签',
  });
  expect(state.writes.every((write) => JSON.parse(write.body).generation === 'g1')).toBe(true);
});

test('lost receipts lock input and retry the identical mutation without another record', async ({
  page,
}, info) => {
  const state = await editingCloud(page);
  state.loseResponse = true;
  await page.goto(appPath('/records/diary_tags/tag/edit'));
  await page.getByLabel(/^名称/).fill('本次修改');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('heading', { name: '提交结果待确认' })).toBeVisible();
  await expect(page.getByLabel(/^名称/)).toBeDisabled();
  await page.screenshot({
    path: `.impeccable/review/editing-${info.project.name}-pending.png`,
    fullPage: false,
  });
  await page.getByRole('button', { name: '确认提交结果' }).click();
  await expect(page.getByRole('heading', { name: '已保存' })).toBeVisible();
  expect(state.writes).toHaveLength(2);
  expect(state.writes[0].body).toBe(state.writes[1].body);
  expect(state.writes[0].body).toContain('9007199254740993');
  expect(state.version).toBe(3);
  await page.getByRole('link', { name: '查看记录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '本次修改', exact: true })).toBeVisible();
});

test('refresh keeps the editing base and repeated conflicts require a new versioned confirmation', async ({
  page,
}, info) => {
  const state = await editingCloud(page);
  state.conflicts = 2;
  await page.goto(appPath('/records/diary_tags/tag/edit'));
  await page.getByLabel(/^名称/).fill('本次争议名称');
  state.rows[0] = {
    ...state.rows[0],
    version: 3,
    snapshot: { ...state.rows[0].snapshot, name: '刷新后的云端名称' },
  };
  await page.getByRole('button', { name: '刷新云端数据' }).click();
  await expect(page.getByLabel(/^名称/)).toHaveValue('本次争议名称');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('heading', { name: '处理冲突', exact: true })).toBeVisible();
  expect(JSON.parse(state.writes[0].body).base_version).toBe(2);
  await page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'reduce' });
  await page.getByRole('button', { name: '语言', exact: true }).click();
  await page.getByRole('menuitemradio', { name: 'English', exact: true }).click();
  await page.locator('#main').evaluate((element) => element.scrollTo(0, 0));
  await page.screenshot({
    path: `.impeccable/review/editing-${info.project.name}-dark-en.png`,
    fullPage: false,
  });
  await page.locator('#main').evaluate((element) => element.scrollTo(0, element.scrollHeight));
  await page.screenshot({
    path: `.impeccable/review/editing-${info.project.name}-conflict-details.png`,
    fullPage: false,
  });
  await page.getByRole('button', { name: 'Keep your conflicting values', exact: true }).click();
  await expect(page.getByText('Current version 4', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Keep cloud conflicting values', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Saved', exact: true })).toBeVisible();
  const mutations = state.writes.map((write) => JSON.parse(write.body));
  expect(new Set(mutations.map((item) => item.mutation_id)).size).toBe(3);
  expect(mutations.map((item) => item.base_version)).toEqual([2, 2, 2]);
  expect(mutations.slice(1).map((item) => item.expected_version)).toEqual([3, 4]);
  expect(mutations.every((item) => item.snapshot.name === '本次争议名称')).toBe(true);
});

test('deletion requires confirmation and sends no snapshot', async ({ page }, info) => {
  const state = await editingCloud(page);
  await page.goto(appPath('/records/diary_tags/tag/edit'));
  await page.getByRole('button', { name: '删除记录', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.screenshot({
    path: `.impeccable/review/editing-${info.project.name}-delete.png`,
    fullPage: false,
  });
  expect(state.writes).toHaveLength(0);
  await page.getByRole('button', { name: '确认删除', exact: true }).click();
  await expect(page.getByRole('heading', { name: '记录已删除' })).toBeVisible();
  expect(state.writes[0].method).toBe('DELETE');
  expect(JSON.parse(state.writes[0].body).snapshot).toBeUndefined();
});

test('delete conflicts require a fresh versioned deletion confirmation', async ({ page }, info) => {
  const state = await editingCloud(page);
  state.conflicts = 1;
  await page.goto(appPath('/records/diary_tags/tag/edit'));
  await page.getByRole('button', { name: '删除记录', exact: true }).click();
  await page.getByRole('button', { name: '确认删除', exact: true }).click();
  await expect(page.getByRole('heading', { name: '处理冲突', exact: true })).toBeVisible();
  await page.locator('#main').evaluate((element) => element.scrollTo(0, element.scrollHeight));
  await page.screenshot({
    path: `.impeccable/review/editing-${info.project.name}-delete-conflict.png`,
    fullPage: false,
  });
  await page.getByRole('button', { name: '确认删除', exact: true }).click();
  await expect(page.getByRole('heading', { name: '记录已删除', exact: true })).toBeVisible();
  const mutations = state.writes.map((write) => JSON.parse(write.body));
  expect(state.writes.map((write) => write.method)).toEqual(['DELETE', 'DELETE']);
  expect(mutations[0].mutation_id).not.toBe(mutations[1].mutation_id);
  expect(mutations.map((mutation) => mutation.base_version)).toEqual([2, 2]);
  expect(mutations[1]).toMatchObject({ resolution: 'local', expected_version: 3 });
  expect(mutations.every((mutation) => mutation.snapshot === undefined)).toBe(true);
});

test('unsaved input stays in memory and generation changes discard the old editor', async ({
  page,
}) => {
  const state = await editingCloud(page);
  await page.goto(appPath('/records/diary_tags/tag/edit'));
  await page.getByLabel(/^名称/).fill('只在页面中的输入');
  await page.getByRole('link', { name: '取消', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('button', { name: '留在此页', exact: true }).click();
  await expect(page.getByLabel(/^名称/)).toHaveValue('只在页面中的输入');
  await page.evaluate(() => window.dispatchEvent(new Event('offline')));
  await expect(page.getByRole('button', { name: '保存', exact: true })).toBeDisabled();
  const keys = await page.evaluate(() => Object.keys(localStorage));
  expect(
    keys.every((key) => ['recoow:preferences', 'recoow:theme', 'recoow:palette'].includes(key)),
  ).toBe(true);
  state.generation = 'g2';
  state.rows[0] = { ...state.rows[0], snapshot: { ...state.rows[0].snapshot, name: '新世代标签' } };
  await page.evaluate(() => window.dispatchEvent(new Event('online')));
  await expect(page.getByLabel(/^名称/)).toHaveValue('新世代标签');
  expect(state.writes).toEqual([]);
});
