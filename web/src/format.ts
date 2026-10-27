/**
 * Money arrives from the API as integer paise. Formatting splits it with integer arithmetic so no
 * fractional value is ever computed in floating point.
 */
const grouping = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });

export function formatPaise(minor: number, currency = 'INR'): string {
  if (!Number.isSafeInteger(minor)) {
    throw new Error(`amount must be an integer number of minor units, got ${minor}`);
  }
  const sign = minor < 0 ? '-' : '';
  const abs = Math.abs(minor);
  const major = Math.trunc(abs / 100);
  const fraction = String(abs % 100).padStart(2, '0');
  const symbol = currency === 'INR' ? '₹' : `${currency} `;
  return `${sign}${symbol}${grouping.format(major)}.${fraction}`;
}

/** Compact form for chart axes: ₹1.2L, ₹3.4Cr. */
export function formatPaiseCompact(minor: number): string {
  const rupees = Math.trunc(Math.abs(minor) / 100);
  const sign = minor < 0 ? '-' : '';
  if (rupees >= 1_00_00_000) return `${sign}₹${(rupees / 1_00_00_000).toFixed(1)}Cr`;
  if (rupees >= 1_00_000) return `${sign}₹${(rupees / 1_00_000).toFixed(1)}L`;
  if (rupees >= 1_000) return `${sign}₹${(rupees / 1_000).toFixed(1)}k`;
  return `${sign}₹${rupees}`;
}

/** Parses a rupee amount typed by a user ("1,234.5") into integer paise, or null. */
export function parseRupeesToPaise(input: string): number | null {
  const s = input.replace(/[,₹\s]/g, '');
  const m = /^(\d+)(?:\.(\d{1,2}))?$/.exec(s);
  if (!m) return null;
  return Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0'));
}

export function humanize(code: string | null | undefined): string {
  if (!code) return '';
  return code.toLowerCase().split('_').map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
}
