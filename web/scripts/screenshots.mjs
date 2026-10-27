// Captures the README screenshots from the running demo (docker compose up + scripts/demo.sh).
//   cd web && DEMO_SUPERVISOR_PASSWORD=... DEMO_ADMIN_PASSWORD=... node scripts/screenshots.mjs
// Writes PNGs to ../docs/img. The simulator step re-runs the default simulation from the UI.
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const BASE = process.env.WEB_URL ?? 'http://localhost:3000';
const OUT = process.env.OUT_DIR ?? fileURLToPath(new URL('../../docs/img/', import.meta.url));
mkdirSync(OUT, { recursive: true });
const shot = (page, name, opts = {}) => page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true, ...opts });

async function login(page, user, password) {
  await page.goto(`${BASE}/login`);
  await page.getByLabel('Username').fill(user);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByTestId('money-at-risk').waitFor({ timeout: 30_000 });
}

// CHROMIUM_PATH lets you reuse an already-installed Chromium instead of `npx playwright install chromium`.
const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
try {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1, colorScheme: 'light' });
  const page = await ctx.newPage();

  await page.goto(`${BASE}/login`);
  await page.getByRole('button', { name: 'Sign in' }).waitFor();
  await shot(page, '01-login', { fullPage: false });

  await login(page, 'supervisor', process.env.DEMO_SUPERVISOR_PASSWORD);
  await page.waitForTimeout(1500); // let Recharts finish its entry animation
  await shot(page, '02-dashboard');

  await page.goto(`${BASE}/exceptions`);
  await page.getByRole('table', { name: 'exceptions' }).locator('tbody tr').first().waitFor();
  await shot(page, '03-exceptions');

  await page.goto(`${BASE}/exceptions?exceptionClass=AMOUNT_MISMATCH&status=OPEN`);
  await page.getByRole('table', { name: 'exceptions' }).locator('tbody tr').first().waitFor();
  await page.getByRole('table', { name: 'exceptions' }).locator('tbody tr td a').first().click();
  await page.getByText('Merchant ledger').waitFor();
  await page.getByRole('button', { name: 'Ask bank (SOAP)' }).click();
  await page.getByTestId('bank-status').waitFor();
  await shot(page, '04-exception-detail');

  const mobile = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, colorScheme: 'light' });
  const m = await mobile.newPage();
  await login(m, 'supervisor', process.env.DEMO_SUPERVISOR_PASSWORD);
  await m.waitForTimeout(1500);
  await shot(m, '05-dashboard-mobile', { fullPage: false });

  const admin = await (await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' })).newPage();
  await login(admin, 'admin', process.env.DEMO_ADMIN_PASSWORD);
  await admin.goto(`${BASE}/simulator`);
  await admin.getByLabel('DUPLICATE_CALLBACK rate').waitFor();
  await admin.getByRole('button', { name: 'Run simulation' }).click();
  await admin.getByRole('button', { name: 'Reconcile now' }).waitFor({ timeout: 120_000 });
  await admin.getByRole('button', { name: 'Reconcile now' }).click();
  await admin.getByTestId('gt-overall').waitFor({ timeout: 300_000 });
  await shot(admin, '06-simulator');
  console.log(`screenshots written to ${OUT}`);
} finally {
  await browser.close();
}
