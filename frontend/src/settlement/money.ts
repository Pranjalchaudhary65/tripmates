/** One expense is at most ten lakh rupees, as the server holds it. */
export const MAX_EXPENSE_PAISE = 100_000_000;

const RUPEES = /^(\d{1,7})(?:\.(\d{1,2}))?$/;

/**
 * "1,800.50" style text as whole paise, read digit by digit so no floating
 * point step can turn 0.29 into 28 paise. Null for anything that is not a
 * plain amount of rupees with at most two decimals, or is zero.
 */
export function rupeesToPaise(text: string): number | null {
  const match = RUPEES.exec(text.trim().replace(/,/g, ''));
  if (match === null) {
    return null;
  }
  const rupees = Number(match[1]);
  const fraction = (match[2] ?? '').padEnd(2, '0');
  const paise = rupees * 100 + Number(fraction);
  return paise > 0 ? paise : null;
}

const FORMAT = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2 });

/** ₹1,800.50, Indian digit grouping. */
export function formatPaise(paise: number): string {
  return FORMAT.format(paise / 100);
}

/**
 * Equal shares exactly as the server works them out: the leftover paise go
 * one each to the first people in user ID order. Used only to preview the
 * split; the server computes it again.
 */
export function equalShares(amountPaise: number, userIds: readonly string[]): Map<string, number> {
  const ordered = [...new Set(userIds)].sort();
  const shares = new Map<string, number>();
  if (ordered.length === 0) {
    return shares;
  }
  const base = Math.floor(amountPaise / ordered.length);
  const remainder = amountPaise % ordered.length;
  ordered.forEach((id, index) => shares.set(id, base + (index < remainder ? 1 : 0)));
  return shares;
}
