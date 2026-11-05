import { dateLocale } from '../i18n';

/** "RM 46.80" in the current language's number style. Currency comes from the claim (the employee's entity). */
export function formatMoney(amount: number | string | null | undefined, currency: string): string {
  const value = Number(amount ?? 0);
  try {
    return new Intl.NumberFormat(dateLocale(), { style: 'currency', currency, currencyDisplay: 'narrowSymbol' }).format(value);
  } catch {
    return `${currency} ${value.toFixed(2)}`;
  }
}

/** The month of an ISO date as YYYY-MM. */
export function monthKey(iso: string): string {
  return iso.slice(0, 7);
}

/** "November 2026" in the current language. */
export function formatMonth(key: string): string {
  const [y, m] = key.split('-').map(Number);
  return new Intl.DateTimeFormat(dateLocale(), { month: 'long', year: 'numeric' }).format(new Date(y, m - 1, 1));
}
