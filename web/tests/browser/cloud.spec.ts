import { appPath } from './paths.js';
import { test, expect, type Page } from '@playwright/test';
import { apiFields } from '../../src/lib/api-fields.js';

const now = 1793923200000;
const row = {
  table: 'diary_entries',
  id: 'diary-1',
  snapshot: { title: '一次离线修改', content: '长文本\n'.repeat(100), updated_at: now },
  version: 2,
  updatedAtMs: now,
  deviceId: 'local-ios',
};
const event = {
  eventId: 'event-1',
  generation: 'g1',
  version: 2,
  previousVersion: 1,
  table: row.table,
  id: row.id,
  title: '一次离线修改',
  operation: 'update',
  method: 'normal',
  operationId: 'op-1',
  triggerEventId: null,
  modifiedAtMs: now,
  acceptedAtMs: now + 3600000,
  originalSource: { client: 'ios', os: 'ios', deviceId: 'local-ios', deviceName: '离线 iPhone' },
  submission: {
    client: 'android',
    os: 'android',
    deviceId: 'registered-android',
    deviceName: '同步 Android',
  },
};

async function cloud(page: Page) {
  const state = {
    signedIn: false,
    username: 'alice',
    account: 'account-a',
    generation: 'g1',
    empty: false,
    expire: false,
    mediaAvailable: false,
    paginated: false,
    reset: false,
    historyRequests: [] as URL[],
    writes: [] as string[],
  };
  await page.route('**/api/**', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const send = (data: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify(apiFields(data, false)),
      });
    if (req.method() !== 'GET' && !url.pathname.startsWith('/api/sessions'))
      state.writes.push(url.pathname);
    if (url.pathname === '/api/sessions') {
      const credentials = req.postDataJSON();
      if (credentials.password === 'wrong') return send({ error: 'invalid credentials' }, 401);
      state.signedIn = true;
      state.username = credentials.username;
      state.account = credentials.username === 'bob' ? 'account-b' : 'account-a';
      return send({
        accountId: state.account,
        userId: 'user',
        username: state.username,
        access: 'read_only',
      });
    }
    if (url.pathname === '/api/sessions/current') {
      state.signedIn = false;
      return send({ ok: true });
    }
    if (!state.signedIn || state.expire) return send({ error: 'unauthorized' }, 401);
    if (url.pathname === '/api/users/me')
      return send({ accountId: state.account, userId: 'user', username: state.username });
    if (url.pathname === '/api/sync/capabilities')
      return send({
        protocolVersion: 1,
        accountId: state.account,
        dataset: { generation: state.generation },
        contracts: {},
        features: ['change_history_v1', 'device_profiles_v1'],
      });
    if (url.pathname === '/api/statistics')
      return route.fulfill({
        contentType: 'application/json',
        body:
          '{"recordCounts":{"diary_entries":' +
          (state.empty ? '0' : '1') +
          '},"billExpenseCents":9007199254740993,"billIncomeCents":0,"billStoredValueUseCents":0}',
      });
    if (url.pathname === '/api/devices')
      return send({
        devices: [
          {
            id: 'registered-android',
            name: '同步 Android',
            isOwner: true,
            lastSeenAt: new Date(now).toISOString(),
            profile: { client: 'android', os: 'android' },
          },
        ],
      });
    if (url.pathname === '/api/change-events') {
      state.historyRequests.push(url);
      if (state.reset) {
        state.reset = false;
        state.generation = 'g2';
        state.empty = true;
        return send({ error: 'reset_required' }, 409);
      }
      const filtered = url.searchParams.get('client') === 'web';
      return send({
        events:
          state.empty || filtered
            ? []
            : [
                {
                  ...event,
                  eventId: url.searchParams.get('cursor') ? 'event-0' : event.eventId,
                  title: url.searchParams.get('cursor') ? '更早的变化' : event.title,
                  generation: state.generation,
                },
              ],
        generation: state.generation,
        watermark: 2,
        more: state.paginated && !url.searchParams.get('cursor'),
        nextCursor: state.paginated && !url.searchParams.get('cursor') ? 'fixed-cursor-g1' : null,
      });
    }
    if (url.pathname === '/api/change-events/event-1')
      return route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          event,
          before: { ...row, version: 1, snapshot: { title: '原标题', note: null } },
          after: row,
          related: [],
          changes: [
            {
              field: 'title',
              beforePresent: true,
              afterPresent: true,
              beforeValue: '原标题',
              afterValue: '一次离线修改',
            },
            {
              field: 'note',
              beforePresent: false,
              afterPresent: true,
              beforeValue: null,
              afterValue: null,
            },
            {
              field: 'amount_cents',
              beforePresent: true,
              afterPresent: true,
              beforeValue: 0,
              afterValue: 'BIG_CENTS',
            },
          ],
        }).replace('"BIG_CENTS"', '9007199254740993'),
      });
    if (url.pathname === '/api/diary-entries')
      return send({
        rows: state.empty
          ? []
          : [
              {
                ...row,
                snapshot: {
                  ...row.snapshot,
                  title: state.account === 'account-b' ? 'Bob 的记录' : row.snapshot.title,
                },
              },
            ],
        nextCursor: '',
        more: false,
      });
    if (url.pathname === '/api/diary-entries/diary-1')
      return send({
        ...row,
        snapshot: {
          ...row.snapshot,
          _media: { object: { key: 'missing.png', contentType: 'image/png' } },
        },
      });
    if (url.pathname.startsWith('/api/media/objects/'))
      return state.mediaAvailable
        ? route.fulfill({
            contentType: 'image/png',
            body: Buffer.from(
              'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jbekAAAAASUVORK5CYII=',
              'base64',
            ),
          })
        : route.fulfill({ status: 404 });
    return send({ error: 'unexpected path' }, 404);
  });
  return state;
}

async function signIn(page: Page, username = 'alice', password = 'correct') {
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
}

async function chooseFilter(page: Page, label: string, option: string) {
  await page.getByRole('combobox', { name: label, exact: true }).click();
  await page.getByRole('option', { name: option, exact: true }).click();
}

async function choosePreference(page: Page, preference: string, option: string) {
  await page.getByRole('button', { name: preference, exact: true }).click();
  await page.getByRole('menuitemradio', { name: option, exact: true }).click();
  await expect(page.getByRole('menu')).toHaveCount(0);
}

test('theme and language follow system, persist, and remain usable with keyboard', async ({
  page,
}, testInfo) => {
  await cloud(page);
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.goto(appPath('/'));
  await expect(page.getByRole('heading', { name: '登录 recoow' })).toBeVisible();
  await expect(page.locator('html')).toHaveClass('dark');
  await choosePreference(page, '主题', '浅色');
  await expect(page.locator('html')).not.toHaveClass('dark');
  await choosePreference(page, '语言', 'English');
  await expect(page.getByRole('heading', { name: 'Sign in to recoow' })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await page.reload();
  const language = page.getByRole('button', { name: 'Language', exact: true });
  await language.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('menuitemradio', { name: 'English', exact: true })).toHaveAttribute(
    'aria-checked',
    'true',
  );
  await expect(page.getByRole('menuitemradio', { name: 'System', exact: true })).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(page.getByRole('menuitemradio', { name: '简体中文', exact: true })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('menu')).toHaveCount(0);
  await expect(language).toBeFocused();
  const theme = page.getByRole('button', { name: 'Theme', exact: true });
  await theme.click();
  await expect(page.getByRole('menuitemradio', { name: 'Light', exact: true })).toHaveAttribute(
    'aria-checked',
    'true',
  );
  await page.screenshot({
    path: `test-results/login-light-en-${testInfo.project.name}.png`,
    fullPage: true,
  });
  // 菜单会在指针事件到达页面元素之前先截获外部点击。
  await page.mouse.click(8, 8);
  await expect(page.getByRole('menu')).toHaveCount(0);
  await choosePreference(page, 'Theme', 'System');
  await expect(page.locator('html')).toHaveClass('dark');
  await page.emulateMedia({ colorScheme: 'light' });
  await expect(page.locator('html')).not.toHaveClass('dark');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.evaluate(() => {
    localStorage.setItem('recoow:preferences', JSON.stringify({ theme: 'dark', language: 'zh' }));
  });
  await page.reload();
  await expect(page.locator('html')).toHaveClass('dark');
  await expect(page.getByRole('heading', { name: '登录 recoow' })).toBeVisible();
  expect(
    await page.evaluate(() => JSON.parse(localStorage.getItem('recoow:preferences') ?? '{}')),
  ).toEqual({ language: 'zh' });
  await page.getByRole('button', { name: '语言', exact: true }).click();
  await page.screenshot({
    path: `test-results/login-dark-zh-${testInfo.project.name}.png`,
    fullPage: true,
  });
  await page.keyboard.press('Escape');
});

test('reads precise amounts, historical times and source snapshots', async ({ page }, testInfo) => {
  const state = await cloud(page);
  await page.goto(appPath('/'));
  await signIn(page);
  await expect(page.getByRole('heading', { name: '记录概览' })).toBeVisible();
  await expect(page.getByText('¥90,071,992,547,409.93', { exact: true })).toBeVisible();
  await page.screenshot({
    path: `test-results/overview-${testInfo.project.name}.png`,
    fullPage: true,
  });
  await page.goto(appPath('/history/event-1'));
  await expect(page.getByRole('heading', { name: '一次离线修改', exact: true })).toBeVisible();
  await expect(page.getByText('iOS App · iOS · 离线 iPhone', { exact: true })).toBeVisible();
  await expect(
    page.getByText('Android App · Android · 同步 Android', { exact: true }),
  ).toBeVisible();
  await expect(page.getByText('未设置', { exact: true })).toBeVisible();
  await expect(page.locator('pre.diff-value.after').filter({ hasText: /^空值$/ })).toBeVisible();
  await expect(page.getByText('¥90,071,992,547,409.93', { exact: true })).toBeVisible();
  expect(state.writes).toEqual([]);
});

test('record history filters and missing media remain readable', async ({ page }) => {
  const state = await cloud(page);
  await page.goto(appPath('/records/diary_entries/diary-1'));
  await signIn(page);
  await page.locator('.media-grid').scrollIntoViewIfNeeded();
  await expect(page.getByText('媒体不可用，可能未上传或已删除')).toBeVisible();
  await page.getByRole('link', { name: '查看全部变化' }).click();
  await expect(page.getByLabel('记录 ID', { exact: true })).toHaveValue('diary-1');
  await expect
    .poll(() => state.historyRequests.some((url) => url.searchParams.get('id') === 'diary-1'))
    .toBe(true);
  await chooseFilter(page, '修改平台', 'Web');
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText('暂无数据')).toBeVisible();
});

test('expired sessions and account switches clear prior records', async ({ page }) => {
  const state = await cloud(page);
  await page.goto(appPath('/records/diary_entries'));
  await signIn(page);
  await expect(page.getByRole('link', { name: '一次离线修改', exact: true })).toBeVisible();
  state.expire = true;
  await page.getByRole('button', { name: '刷新云端数据' }).click();
  await expect(page.getByText('登录已过期，请重新登录')).toBeVisible();
  await expect(page.getByRole('link', { name: '一次离线修改', exact: true })).toHaveCount(0);
  state.expire = false;
  await signIn(page, 'bob');
  await expect(page.getByRole('link', { name: 'Bob 的记录', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '一次离线修改', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '账号菜单' }).click();
  await page.getByRole('menuitem', { name: '退出登录' }).click();
  await expect(page.getByRole('heading', { name: '登录 recoow' })).toBeVisible();
});

test('reset history clears previous generation and reloads the new dataset', async ({ page }) => {
  const state = await cloud(page);
  await page.goto(appPath('/history'));
  await signIn(page);
  await expect(page.getByRole('link', { name: '一次离线修改', exact: true })).toBeVisible();
  state.reset = true;
  await page.getByRole('button', { name: '刷新云端数据' }).click();
  await expect(page.getByText('暂无数据')).toBeVisible();
  await expect(page.getByRole('link', { name: '一次离线修改', exact: true })).toHaveCount(0);
});

test('incorrect credentials and offline state show distinct messages', async ({ page }) => {
  await cloud(page);
  await page.goto(appPath('/'));
  await signIn(page, 'alice', 'wrong');
  await expect(page.getByText('用户名或密码错误')).toBeVisible();
  await expect(page.getByText('登录已过期，请重新登录')).toHaveCount(0);
  await signIn(page);
  await expect(page.getByRole('heading', { name: '记录概览' })).toBeVisible();
  await page.evaluate(() => window.dispatchEvent(new Event('offline')));
  await expect(page.getByText('当前离线，联网后会更新数据。')).toBeVisible();
  await expect(page.getByRole('button', { name: '刷新云端数据' })).toBeDisabled();
});

test('media preview supports keyboard closing and dark English layout', async ({
  page,
}, testInfo) => {
  const state = await cloud(page);
  state.mediaAvailable = true;
  await page.goto(appPath('/records/diary_entries/diary-1'));
  await signIn(page);
  await page.getByRole('button', { name: '放大图片' }).click();
  await expect(page.getByRole('dialog', { name: '图片预览' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '放大图片' })).toBeFocused();
  await choosePreference(page, '主题', '深色');
  await choosePreference(page, '语言', 'English');
  await expect(page.getByRole('heading', { name: 'Record content' })).toBeVisible();
  await expect(page.locator('html')).toHaveClass('dark');
  const storageKeys = await page.evaluate(() => Object.keys(localStorage));
  expect(storageKeys).toContain('recoow:preferences');
  expect(
    storageKeys.every((key) =>
      ['recoow:preferences', 'recoow:theme', 'recoow:palette'].includes(key),
    ),
  ).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.screenshot({
    path: `test-results/detail-dark-en-${testInfo.project.name}.png`,
    fullPage: true,
  });
});

test('history continuation keeps filters and the first cursor', async ({ page }) => {
  const state = await cloud(page);
  state.paginated = true;
  await page.goto(appPath('/history'));
  await signIn(page);
  await chooseFilter(page, '修改平台', 'iOS App');
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await page.getByRole('button', { name: '加载更多', exact: true }).click();
  await expect(page.getByRole('link', { name: '更早的变化', exact: true })).toBeVisible();
  const next = state.historyRequests.find(
    (url) => url.searchParams.get('cursor') === 'fixed-cursor-g1',
  );
  expect(next?.searchParams.get('client')).toBe('ios');
  expect(next?.searchParams.get('generation')).toBe('g1');
});

test('client categories and child types preserve navigation and keyboard controls', async ({
  page,
}, testInfo) => {
  await cloud(page);
  await page.goto(appPath('/records'));
  await signIn(page);
  const directory = page.locator('.record-directory');
  const categories = [
    '轨迹记录',
    '选什么',
    '在哪里',
    '打卡任务',
    '记一笔',
    '饮食记录',
    '日记',
    '纪念日',
    '素材库',
  ];
  for (const name of categories)
    await expect(directory.getByRole('heading', { name, exact: true })).toBeVisible();
  await expect(directory.locator('.directory-links a')).toHaveCount(21);
  await directory.locator('.directory-title').filter({ hasText: '日记' }).click();
  await expect(page).toHaveURL(/records\/diary_entries$/);
  await expect(page.locator('.record-tabs a')).toHaveCount(3);
  if (testInfo.project.name === 'desktop') {
    await page.getByRole('button', { name: '收起侧栏' }).click();
    await expect(page.locator('.app-shell')).toHaveClass(/sidebar-collapsed/);
    await expect.poll(async () => (await page.locator('.sidebar').boundingBox())?.width).toBe(76);
    await expect(page.locator('.sidebar .brand-name')).toBeHidden();
    await expect(page.locator('.sidebar .nav-list a span').first()).toBeHidden();
    await page.getByRole('button', { name: '展开侧栏' }).click();
    await expect.poll(async () => (await page.locator('.sidebar').boundingBox())?.width).toBe(224);
  } else {
    await page.getByRole('button', { name: '打开导航' }).click();
    const drawer = page.getByRole('dialog');
    await expect(drawer.getByRole('link', { name: '打卡任务', exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(drawer).toHaveCount(0);
    await expect(page.getByRole('button', { name: '打开导航' })).toBeFocused();
  }
  await chooseFilter(page, '记录分类', '选什么');
  await expect(page).toHaveURL(/records\/decision_collections$/);
  await page.locator('.record-tabs').getByRole('link', { name: '候选项', exact: true }).click();
  await expect(page).toHaveURL(/records\/decision_options$/);
  await expect(page.locator('select, input[type=date], details, summary')).toHaveCount(0);
});

test('custom calendar and reset preserve date filtering and allow clearing', async ({ page }) => {
  const state = await cloud(page);
  await page.goto(appPath('/history'));
  await signIn(page);
  await page.getByRole('button', { name: '开始日期', exact: true }).click();
  await expect(page.locator('.calendar-popover')).toBeVisible();
  const day = page.locator('.calendar-day:not([data-disabled]):not([data-outside-month])').first();
  await day.click();
  await expect(page.locator('.calendar-popover')).toHaveCount(0);
  const selected = await page.getByRole('button', { name: '开始日期', exact: true }).innerText();
  await page.getByRole('button', { name: '结束日期', exact: true }).click();
  const sameDay = page
    .locator('.calendar-day:not([data-disabled]):not([data-outside-month])')
    .first();
  await sameDay.focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('.calendar-popover')).toHaveCount(0);
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect
    .poll(() => state.historyRequests.some((url) => url.searchParams.has('from_ms')))
    .toBe(true);
  const request = state.historyRequests.findLast((url) => url.searchParams.has('from_ms'))!;
  const from = Number(request.searchParams.get('from_ms'));
  const to = Number(request.searchParams.get('to_ms'));
  expect(to - from).toBe(86400000);
  expect(selected).toMatch(/\d{4}\/\d{2}\/\d{2}/);
  await page.getByRole('button', { name: '清除开始日期' }).click();
  await expect(page.getByRole('button', { name: '开始日期', exact: true })).toHaveText(/选择日期/);
  await page.getByRole('button', { name: '重置', exact: true }).click();
  await expect(page.getByRole('button', { name: '结束日期', exact: true })).toHaveText(/选择日期/);
  await expect(page.locator('select, input[type=date]')).toHaveCount(0);
});

test('password visibility, long content and source disclosures keep their content accessible', async ({
  page,
}) => {
  await cloud(page);
  await page.goto(appPath('/records/diary_entries/diary-1'));
  await page.getByLabel('密码', { exact: true }).fill('correct');
  await page.getByRole('button', { name: '显示密码' }).click();
  await expect(page.getByLabel('密码', { exact: true })).toHaveAttribute('type', 'text');
  await page.getByRole('button', { name: '隐藏密码' }).click();
  await expect(page.getByLabel('密码', { exact: true })).toHaveAttribute('type', 'password');
  await signIn(page);
  const content = page.locator('.expandable-copy');
  await expect(content).toHaveText(/展开完整内容/);
  await content.getByRole('button', { name: '展开完整内容' }).click();
  await expect(content.getByRole('button')).toHaveAttribute('aria-expanded', 'true');
  expect((await content.innerText()).split('长文本').length).toBe(101);
  await content.getByRole('button', { name: '收起内容' }).click();
  await page.goto(appPath('/history/event-1'));
  await page.getByRole('button', { name: '来源详情' }).click();
  await expect(page.getByText('修改时间和编辑来源由设备提供。', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: '历史快照' }).click();
  await expect(page.getByRole('heading', { name: '修改前', exact: true })).toBeVisible();
});

test('workspace scrolling keeps header and sidebar in place and resets on navigation', async ({
  page,
}, testInfo) => {
  await cloud(page);
  await page.goto(appPath('/history'));
  await signIn(page);
  await page.setViewportSize({
    width: testInfo.project.name === 'desktop' ? 1440 : 390,
    height: 500,
  });
  const main = page.locator('main.main');
  const before = await page.locator('.topbar').boundingBox();
  const sidebarBefore =
    testInfo.project.name === 'desktop' ? await page.locator('.sidebar').boundingBox() : null;
  await main.evaluate((node) => (node.scrollTop = node.scrollHeight));
  expect(await main.evaluate((node) => node.scrollTop)).toBeGreaterThan(0);
  expect(await page.locator('.topbar').boundingBox()).toEqual(before);
  if (sidebarBefore) expect(await page.locator('.sidebar').boundingBox()).toEqual(sidebarBefore);
  expect(await page.evaluate(() => window.scrollY)).toBe(0);
  await main.getByRole('link', { name: '查看变化', exact: true }).first().click();
  await expect.poll(() => main.evaluate((node) => node.scrollTop)).toBe(0);
});

test('navigation and icons stay within the app deployment path', async ({ page, isMobile }) => {
  await cloud(page);
  await page.goto(appPath('/records'));
  const checkIcons = async (dark: boolean) => {
    const icon = page.locator('.app-icon:visible').first();
    const name = dark ? 'app-icon-dark.png' : 'app-icon.png';
    await expect(icon).toHaveAttribute('src', appPath('/' + name));
    await expect
      .poll(() => icon.evaluate((node: HTMLImageElement) => node.complete && node.naturalWidth > 0))
      .toBe(true);
    const url = new URL((await icon.getAttribute('src'))!, page.url());
    expect(url.origin).toBe(new URL(page.url()).origin);
    for (const selector of ['link[rel="icon"]', 'link[rel="apple-touch-icon"]']) {
      const href = await page.locator(selector).getAttribute('href');
      const url = new URL(href!, page.url());
      expect(url.origin).toBe(new URL(page.url()).origin);
      expect(url.pathname.startsWith(appPath('/'))).toBe(true);
      const response = await page.request.get(url.href);
      expect(response.status()).toBe(200);
      expect(response.headers()['content-type']).toContain('image/png');
    }
  };
  await page.getByRole('button', { name: '主题', exact: true }).click();
  await page.getByRole('menuitemradio', { name: '浅色', exact: true }).click();
  await checkIcons(false);
  await page.getByRole('button', { name: '主题', exact: true }).click();
  await page.getByRole('menuitemradio', { name: '深色', exact: true }).click();
  await checkIcons(true);
  await signIn(page);
  if (isMobile) await page.getByRole('button', { name: '打开导航', exact: true }).click();
  await checkIcons(true);
  if (isMobile) await page.getByRole('button', { name: '关闭', exact: true }).click();
  const links = await page
    .locator('a[href]')
    .evaluateAll((nodes) => nodes.map((node) => node.getAttribute('href')!));
  for (const href of links) {
    if (href.startsWith('#')) continue;
    expect(href.startsWith(appPath('/'))).toBe(true);
  }
  await page.goto(appPath('/history/event-1'));
  await expect(page.getByRole('link', { name: '返回', exact: true })).toHaveAttribute(
    'href',
    appPath('/history'),
  );
  await page.getByRole('link', { name: '返回', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`${appPath('/history')}$`));
  if (isMobile) await page.getByRole('button', { name: '打开导航', exact: true }).click();
  await checkIcons(true);
});
