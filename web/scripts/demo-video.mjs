// Records a walkthrough of every feature against the running stack (docker compose up).
//   cd web && DEMO_ANALYST_PASSWORD=... DEMO_SUPERVISOR_PASSWORD=... DEMO_ADMIN_PASSWORD=... \
//     node scripts/demo-video.mjs
// Produces a .webm in ../docs/video/raw/; scripts/make-video.sh converts it to MP4 + GIF.
// The walkthrough resets the demo data (it runs the simulator).
import { chromium } from 'playwright';
import { mkdirSync, readdirSync, renameSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const WEB = process.env.WEB_URL ?? 'http://localhost:3000';
const API = process.env.API ?? 'http://localhost:8080';
const BANK = process.env.BANK_URL ?? 'http://localhost:8082';
const OUT = fileURLToPath(new URL('../../docs/video/raw/', import.meta.url));
const PW = {
  analyst: process.env.DEMO_ANALYST_PASSWORD,
  supervisor: process.env.DEMO_SUPERVISOR_PASSWORD,
  admin: process.env.DEMO_ADMIN_PASSWORD,
};
rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });
const W = 1280, H = 800;

// ---------------------------------------------------------------- API helpers (to find example records)
async function token(user) {
  const r = await fetch(`${API}/api/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: user, password: PW[user] }) });
  return (await r.json()).token;
}
async function apiGet(path, user = 'supervisor') {
  const r = await fetch(`${API}${path}`, { headers: { Authorization: `Bearer ${await token(user)}` } });
  return r.json();
}
async function firstException(query) {
  const p = await apiGet(`/api/exceptions?size=1&${query}`);
  return p.content[0]?.id;
}

// ---------------------------------------------------------------- on-screen helpers
const pause = (page, ms) => page.waitForTimeout(ms);

async function caption(page, step, title, text) {
  await page.evaluate(([step, title, text]) => {
    let el = document.getElementById('__caption');
    if (!el) {
      el = document.createElement('div');
      el.id = '__caption';
      el.style.cssText = 'position:fixed;left:50%;bottom:22px;transform:translateX(-50%);max-width:1100px;width:calc(100% - 80px);'
        + 'background:rgba(15,23,42,.92);color:#fff;border-radius:12px;padding:14px 20px;z-index:2147483646;'
        + 'font:15px/1.45 Inter,system-ui,sans-serif;box-shadow:0 10px 30px rgba(0,0,0,.35);pointer-events:none';
      document.body.appendChild(el);
    }
    el.innerHTML = `<div style="font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:#93c5fd;margin-bottom:4px">${step}</div>`
      + `<div style="font-weight:700;font-size:18px;margin-bottom:2px">${title}</div><div style="color:#e2e8f0">${text}</div>`;
  }, [step, title, text]);
}

async function hideCaption(page) {
  await page.evaluate(() => document.getElementById('__caption')?.remove());
}

async function moveTo(page, locator) {
  await locator.scrollIntoViewIfNeeded();
  const b = await locator.boundingBox();
  if (!b) return;
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 18 });
}

async function click(page, locator, wait = 500) {
  await moveTo(page, locator);
  await pause(page, 250);
  await locator.click();
  await pause(page, wait);
}

async function type(page, locator, text) {
  await click(page, locator, 150);
  await locator.fill('');
  await locator.pressSequentially(text, { delay: 55 });
}

async function scroll(page, dy, steps = 12) {
  for (let i = 0; i < steps; i++) {
    await page.mouse.wheel(0, dy / steps);
    await pause(page, 45);
  }
}

async function card(page, html, ms) {
  await page.setContent(`<!doctype html><html><body style="margin:0;height:100vh;display:grid;place-items:center;
    background:linear-gradient(135deg,#0f172a,#1e3a8a);color:#fff;font-family:Inter,system-ui,sans-serif">
    <div style="max-width:980px;padding:40px">${html}</div></body></html>`);
  await pause(page, ms);
}

async function login(page, user, password = PW[user]) {
  await page.goto(`${WEB}/login`);
  await page.getByRole('button', { name: 'Sign in' }).waitFor();
  await type(page, page.getByLabel('Username'), user);
  await type(page, page.getByLabel('Password'), password);
  await click(page, page.getByRole('button', { name: 'Sign in' }), 300);
}

async function signOut(page) {
  await click(page, page.getByRole('button', { name: 'Sign out' }), 600);
}

async function openFirstRow(page) {
  const link = page.getByRole('table', { name: 'exceptions' }).locator('tbody tr td a').first();
  await link.waitFor();
  await click(page, link, 300);
  await page.getByText('Merchant ledger').waitFor();
  await pause(page, 600);
}

async function detailTour(page, step, title, text, { soap = false } = {}) {
  await caption(page, step, title, text);
  await page.mouse.move(W / 2, 330, { steps: 10 });
  await pause(page, 2600);
  await scroll(page, 260);
  await pause(page, 1200);
  if (soap) {
    const btn = page.getByRole('button', { name: 'Ask bank (SOAP)' });
    await click(page, btn, 300);
    await page.getByTestId('bank-status').waitFor();
    // keep the SOAP answer above the caption bar
    await page.getByTestId('bank-status').evaluate((el) => el.scrollIntoView({ block: 'center', behavior: 'smooth' }));
    await pause(page, 600);
    await moveTo(page, page.getByTestId('bank-status'));
    await pause(page, 2800);
  }
}

// ---------------------------------------------------------------- the walkthrough
const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
const context = await browser.newContext({
  viewport: { width: W, height: H }, colorScheme: 'light',
  recordVideo: { dir: OUT, size: { width: W, height: H } },
});
await context.addInitScript(() => {
  // A visible cursor, since headless recordings do not show the pointer.
  const add = () => {
    if (document.getElementById('__cursor')) return;
    const c = document.createElement('div');
    c.id = '__cursor';
    c.style.cssText = 'position:fixed;left:-50px;top:-50px;width:22px;height:22px;border-radius:50%;'
      + 'background:rgba(239,68,68,.35);border:2px solid #ef4444;transform:translate(-50%,-50%);'
      + 'pointer-events:none;z-index:2147483647;transition:transform .08s';
    document.documentElement.appendChild(c);
    document.addEventListener('mousemove', (e) => { c.style.left = e.clientX + 'px'; c.style.top = e.clientY + 'px'; }, true);
    document.addEventListener('mousedown', () => { c.style.transform = 'translate(-50%,-50%) scale(.6)'; }, true);
    document.addEventListener('mouseup', () => { c.style.transform = 'translate(-50%,-50%)'; }, true);
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', add); else add();
});
const page = await context.newPage();

try {
  // 0. Title
  await card(page, `
    <div style="font-size:14px;letter-spacing:.12em;text-transform:uppercase;color:#93c5fd">Walkthrough · recorded live against docker compose</div>
    <h1 style="font-size:44px;margin:12px 0 8px">Payment Reconciliation Engine</h1>
    <p style="font-size:20px;color:#cbd5e1;margin:0 0 24px">Merchant ledger vs payment gateway vs bank statement, with a failure simulator that proves the engine against ground truth.</p>
    <ol style="font-size:17px;line-height:1.8;color:#e2e8f0;margin:0;padding-left:22px">
      <li>JWT login and roles (analyst, supervisor, admin)</li>
      <li>Failure simulator: inject 11 fault types, then score the engine against the ground truth</li>
      <li>Money-at-risk dashboard: trend, breakdown by type, aging, auto-fixes</li>
      <li>Ops queue: filters, pagination, every exception class side by side, live SOAP bank check</li>
      <li>Analyst workflow (assign, comment, resolve) and supervisor approval of adjustments</li>
      <li>Settlement grace window: expected in transit vs money at risk</li>
      <li>REST API (OpenAPI / Swagger) and the bank's SOAP contract</li>
    </ol>`, 7000);

  // 1. Login + roles
  await page.goto(`${WEB}/login`);
  await caption(page, '1 · Security', 'JWT login with role-based access',
    'Three seeded demo users: OPS_ANALYST, OPS_SUPERVISOR and ADMIN. A wrong password is rejected with the standard error format.');
  await pause(page, 1500);
  await login(page, 'admin', 'not-the-password');
  await page.getByRole('alert').waitFor();
  await moveTo(page, page.getByRole('alert'));
  await pause(page, 2200);
  await login(page, 'admin');
  await page.getByTestId('money-at-risk').waitFor();
  await pause(page, 800);

  // 2. Simulator + ground truth
  await click(page, page.getByRole('link', { name: 'Simulator', exact: true }), 600);
  await page.getByLabel('DUPLICATE_CALLBACK rate').waitFor();
  await caption(page, '2 · Failure simulator', 'Seeded fake gateway + fake bank that misbehave on purpose',
    'Seed, days, volume and the rate of each of the 11 fault types are configurable. The same seed always produces the same payments, faults and ground-truth file.');
  await pause(page, 3000);
  await type(page, page.getByLabel('Payments / day'), '3000');
  await type(page, page.getByLabel('MISSING_CALLBACK rate'), '1');
  await type(page, page.getByLabel('DUPLICATE_BANK_CREDIT rate'), '0.5');
  await scroll(page, 380);
  await caption(page, '2 · Failure simulator', 'Run: callbacks go to Kafka, settlement files and statements to S3',
    'gateway-sim publishes webhook callbacks (with duplicates and out-of-order events) to Kafka and writes daily settlement CSVs to LocalStack S3; bank-sim writes the bank statements.');
  await click(page, page.getByRole('button', { name: 'Run simulation' }), 300);
  await page.getByRole('button', { name: 'Reconcile now' }).waitFor({ timeout: 180_000 });
  await scroll(page, 400);
  await pause(page, 1500);
  await caption(page, '2 · Reconciliation', 'Reconcile: ingest the three sources, match, classify, auto-fix',
    'The run waits for the Kafka ledger consumer, ingests the files idempotently, then reconciles each (business date × merchant) partition in parallel.');
  await click(page, page.getByRole('button', { name: 'Reconcile now' }), 300);
  await page.getByTestId('gt-overall').waitFor({ timeout: 300_000 });
  await moveTo(page, page.getByTestId('gt-overall'));
  await scroll(page, 500);
  await caption(page, '2 · Proof', 'Engine findings vs injected faults: precision and recall per fault type',
    'Every injected fault was detected with the expected class, and no clean payment was flagged.');
  await pause(page, 5500);

  // 3. Dashboard
  await click(page, page.getByRole('link', { name: 'Dashboard', exact: true }), 1200);
  await caption(page, '3 · Dashboard', 'Money at risk: the sum of unresolved exception amounts',
    'All amounts are integer paise end to end, formatted in Indian grouping. Exceptions handled by auto-fix rules are not counted.');
  await page.mouse.move(200, 160, { steps: 12 });
  await pause(page, 3500);
  await caption(page, '3 · Dashboard', '14-day trend and breakdown by exception type',
    'Click any exception type to jump to a filtered queue.');
  await page.mouse.move(380, 420, { steps: 14 });
  await pause(page, 1800);
  await page.mouse.move(1000, 420, { steps: 14 });
  await pause(page, 2000);
  await scroll(page, 520);
  await caption(page, '3 · Dashboard', 'Aging buckets and what was handled automatically',
    'Duplicate callbacks are deduped, credits that settled within grace are closed, and small fee/rounding differences are posted to the adjustment journal.');
  await pause(page, 4500);

  // 4. Exceptions list: filters + pagination
  await click(page, page.getByRole('link', { name: 'Exceptions', exact: true }), 800);
  await page.getByRole('table', { name: 'exceptions' }).locator('tbody tr').first().waitFor();
  await caption(page, '4 · Ops queue', 'Exceptions: filter, sort and paginate',
    'Default view: unresolved exceptions, largest amount at risk first.');
  await pause(page, 2800);
  await click(page, page.getByLabel('Type'), 200);
  await page.getByLabel('Type').selectOption('AMOUNT_MISMATCH');
  await type(page, page.getByLabel('Min at risk (₹)'), '100');
  await click(page, page.getByLabel('Sort'), 200);
  await page.getByLabel('Sort').selectOption('businessDate,asc');
  await click(page, page.getByRole('button', { name: 'Apply' }), 1200);
  await caption(page, '4 · Ops queue', 'Filtered: amount mismatches of ₹100 or more, oldest first',
    'Filters also cover status, merchant, date range and payment reference, and pagination is server-side.');
  await pause(page, 2800);
  await click(page, page.getByRole('button', { name: 'Next' }), 1200);
  await pause(page, 1500);

  // 5. Every exception class, side by side
  const classes = [
    ['MISSING_IN_LEDGER', 'OPEN', 'Missing in ledger', 'The gateway settled the payment and the bank credited it, but the merchant never received the callback.', false],
    ['MISSING_IN_GATEWAY', 'OPEN', 'Missing in gateway', 'The ledger recorded the payment, but it is in no gateway settlement file, so there is nothing to settle.', false],
    ['MISSING_IN_BANK', 'OPEN', 'Missing in bank', 'The gateway says it settled, the grace window expired and no credit arrived. The live SOAP lookup confirms the bank has no credit for this UTR.', true],
    ['DUPLICATE', 'OPEN', 'Duplicate bank credit', 'The same UTR was credited twice. The bank\'s SOAP service confirms DUPLICATE_CREDIT.', true],
    ['AMOUNT_MISMATCH', 'OPEN', 'Amount mismatch beyond tolerance', 'The ledger and gateway amounts differ by more than ₹1.00, so the case goes to the ops queue.', false],
    ['ORPHAN_BANK_CREDIT', 'OPEN', 'Orphan bank credit', 'Money arrived with a UTR that matches no gateway or ledger record.', false],
    ['LATE_SETTLEMENT', 'AUTO_RESOLVED', 'Late settlement, auto-resolved', 'The credit arrived after T+2 but inside the T+5 grace window, so an auto-fix rule closed it with an audit entry.', false],
    ['DUPLICATE', 'AUTO_RESOLVED', 'Duplicate callback, deduped', 'The gateway sent the same webhook several times. The dedupe table absorbed it; no money effect.', false],
  ];
  let i = 0;
  for (const [cls, status, title, text, soap] of classes) {
    i++;
    const sort = cls === 'DUPLICATE' && status === 'OPEN' ? '' : '&sort=businessDate,asc';
    await page.goto(`${WEB}/exceptions?exceptionClass=${cls}&status=${status}${sort}`);
    await openFirstRow(page);
    await detailTour(page, `5 · Exception classes (${i}/${classes.length})`, title, text, { soap });
  }

  // 6. Analyst workflow + approval rules
  const pending = await apiGet('/api/adjustments?status=PENDING_APPROVAL&size=1');
  const adjExc = pending.content[0]?.exceptionId;
  const caseId = await firstException('exceptionClass=MISSING_IN_LEDGER&status=OPEN&sort=businessDate,desc');
  await page.goto(`${WEB}/`);
  await signOut(page);
  await login(page, 'analyst');
  await page.getByTestId('money-at-risk').waitFor();
  await caption(page, '6 · Roles', 'Signed in as OPS_ANALYST',
    'Analysts work the queue. They cannot run reconciliation (the button is hidden and the API returns 403), read the audit log or approve adjustments.');
  await pause(page, 3800);
  if (adjExc) {
    await page.goto(`${WEB}/exceptions/${adjExc}`);
    await page.getByText('Merchant ledger').waitFor();
    await caption(page, '6 · Adjustments', 'Fee/rounding difference above ₹0.50: needs a supervisor',
      'Within the ₹1.00 tolerance the engine books a signed adjustment journal entry. Up to ₹0.50 it posts automatically; above that an OPS_SUPERVISOR must approve.');
    await scroll(page, 330);
    await moveTo(page, page.getByText(/needs an OPS_SUPERVISOR/));
    await pause(page, 4000);
  }
  await page.goto(`${WEB}/exceptions/${caseId}`);
  await page.getByText('Merchant ledger').waitFor();
  await caption(page, '6 · Analyst workflow', 'Work a case: assign, comment, resolve',
    'Each action changes the case status (OPEN → IN_REVIEW → RESOLVED) and is written to the append-only audit log.');
  await scroll(page, 330);
  await click(page, page.getByRole('button', { name: 'Assign to me' }), 900);
  await type(page, page.getByLabel('comment'), 'Gateway confirms capture; callback was lost. Posting ledger entry.');
  await click(page, page.getByRole('button', { name: 'Add comment' }), 900);
  await click(page, page.getByLabel('resolution reason'), 200);
  await page.getByLabel('resolution reason').selectOption('LEDGER_CORRECTED');
  await type(page, page.getByLabel('resolution note'), 'manual ledger entry LE-2026-0042');
  await click(page, page.getByRole('button', { name: 'Resolve' }), 1200);
  await scroll(page, 500);
  await caption(page, '6 · Audit trail', 'Resolved, with the full history in the audit log',
    'The audit log is append-only: a database trigger rejects UPDATE, DELETE and TRUNCATE. Re-runs never overwrite a human decision.');
  await pause(page, 4500);

  // 7. Supervisor approves, re-runs reconciliation
  await signOut(page);
  await login(page, 'supervisor');
  await page.getByTestId('money-at-risk').waitFor();
  if (adjExc) {
    await page.goto(`${WEB}/exceptions/${adjExc}`);
    await page.getByText('Merchant ledger').waitFor();
    await scroll(page, 330);
    await caption(page, '7 · Supervisor', 'OPS_SUPERVISOR approves the adjustment',
      'Approving posts the journal entry and resolves the exception.');
    await click(page, page.getByRole('button', { name: 'Approve adjustment' }), 1500);
    await moveTo(page, page.getByText(/by supervisor/).first());
    await pause(page, 3000);
  }
  await page.goto(`${WEB}/`);
  await page.getByTestId('money-at-risk').waitFor();
  await caption(page, '7 · Idempotency', 'Re-run reconciliation over the same dates',
    'Re-running changes nothing: same result digest, no new exceptions, and the resolved cases stay resolved.');
  await click(page, page.getByRole('button', { name: 'Run reconciliation' }), 300);
  await page.getByRole('status').waitFor({ timeout: 120_000 });
  await moveTo(page, page.getByRole('status'));
  await pause(page, 3500);

  // 8. Live day: grace window
  await signOut(page);
  await login(page, 'admin');
  await page.getByTestId('money-at-risk').waitFor();
  await click(page, page.getByRole('link', { name: 'Simulator', exact: true }), 600);
  await page.getByLabel('DUPLICATE_CALLBACK rate').waitFor();
  await caption(page, '8 · Grace window', 'Simulate a live day: bank statements stop today',
    'Credits for the newest business dates have not arrived yet. They are due by T+2 and tolerated until T+5.');
  await click(page, page.getByRole('checkbox', { name: /Deliver every bank statement/ }), 800);
  await scroll(page, 380);
  await click(page, page.getByRole('button', { name: 'Run simulation' }), 300);
  await page.getByRole('button', { name: 'Reconcile now' }).waitFor({ timeout: 180_000 });
  await click(page, page.getByRole('button', { name: 'Reconcile now' }), 300);
  await page.getByRole('link', { name: 'Open dashboard' }).waitFor({ timeout: 300_000 });
  await click(page, page.getByRole('link', { name: 'Open dashboard' }), 1500);
  await caption(page, '8 · Grace window', '"Expected in transit" is separate from money at risk',
    'Missing credits inside the grace window are PENDING_EXPECTED and re-checked on every run; they escalate to the queue when the window expires.');
  await moveTo(page, page.getByText('Expected in transit').first());
  await pause(page, 3500);
  await page.mouse.move(380, 430, { steps: 14 });
  await pause(page, 2500);
  await page.goto(`${WEB}/exceptions?status=PENDING_EXPECTED&sort=businessDate,desc`);
  await page.getByRole('table', { name: 'exceptions' }).locator('tbody tr').first().waitFor();
  await caption(page, '8 · Grace window', 'Pending expected credits, newest business dates first', 'These leave the queue on their own if the credit arrives in time.');
  await pause(page, 3500);

  // 9. API surface
  await page.goto(`${API}/swagger-ui/index.html`);
  await page.locator('.opblock').first().waitFor({ timeout: 30_000 });
  await caption(page, '9 · REST API', 'OpenAPI / Swagger UI',
    'Login, runs, exceptions with filters and pagination, actions, adjustments, dashboard, audit log, simulator control and ground-truth evaluation. Every error uses one RFC 7807 problem+json format.');
  await page.mouse.move(640, 300, { steps: 12 });
  await pause(page, 2000);
  await scroll(page, 900, 20);
  await pause(page, 2500);
  // Chromium renders raw XML without an HTML body, so show the live WSDL inside a page.
  const wsdl = await (await fetch(`${BANK}/ws/settlement.wsdl`)).text();
  const esc = wsdl.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/></g, '>\n<');
  await page.setContent(`<!doctype html><html><body style="margin:0;background:#0b1220;color:#cbd5e1;font:13px/1.5 ui-monospace,Menlo,monospace">
    <div style="padding:14px 24px;background:#111827;color:#93c5fd;font:600 14px Inter,system-ui">GET ${BANK}/ws/settlement.wsdl (live)</div>
    <pre style="margin:0;padding:18px 24px;white-space:pre-wrap">${esc.replace(/&gt;&lt;/g, '&gt;\n&lt;')}</pre></body></html>`);
  await caption(page, '9 · SOAP', 'bank-sim SOAP contract: getSettlementStatus(utr)',
    'Spring Web Services endpoint with a WSDL generated from the XSD. recon-service calls it with a JAXB client from the exception detail page.');
  await pause(page, 4000);

  // 10. Outro
  await hideCaption(page);
  await card(page, `
    <h1 style="font-size:36px;margin:0 0 18px">What was measured</h1>
    <ul style="font-size:19px;line-height:1.9;color:#e2e8f0;margin:0;padding-left:22px">
      <li>Ground truth, docker compose, 7 × 20,000 payments: <b>precision 1.0, recall 1.0</b></li>
      <li>14 × 100,000 payments: <b>precision 0.999949, recall 0.999974</b> (4 ambiguous entities sent to the queue)</li>
      <li>1.4M entities reconciled in <b>38.3 s at parallelism 1 and 16.9 s at parallelism 8</b>, with identical result digests</li>
      <li>79 backend tests (Testcontainers Postgres + Kafka) and 38 frontend tests, green in GitHub Actions</li>
    </ul>
    <p style="font-size:16px;color:#93c5fd;margin-top:26px">github.com/himanshu-0807/payment-reconciliation-engine · docs/BENCHMARKS.md has the commands</p>`, 7000);
} finally {
  await context.close();
  await browser.close();
}
const file = readdirSync(OUT).filter((f) => f.endsWith('.webm')).map((f) => `${OUT}${f}`).pop();
if (file) {
  renameSync(file, `${OUT}walkthrough.webm`);
  console.log(`recorded ${OUT}walkthrough.webm`);
}
