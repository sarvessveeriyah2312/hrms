import type { PayrollDocument, TaxFormCode } from '@hrms/api-client';
import i18n from '../../i18n';
import { errorText } from '../../lib/access';
import { formatMonth } from '../../lib/money';

/** "October 2026" for a payslip, "EA form 2025" for a tax form. */
export function periodLabel(doc: Pick<PayrollDocument, 'kind' | 'period' | 'formCode'>): string {
  if (doc.kind === 'PAYSLIP') return formatMonth(doc.period);
  return i18n.t('payroll.formYear', { form: formName(doc.formCode), year: doc.period });
}

export function formName(code: TaxFormCode | null): string {
  return i18n.t(`payroll.forms.${code ?? 'OTHER'}`);
}

/** Opens a file fetched with the session in a new tab (opened inside the click so pop-up blockers allow it). */
export async function openBlob(load: () => Promise<Blob>, onError: (message: string | null) => void) {
  onError(null);
  const tab = window.open('', '_blank');
  try {
    const url = URL.createObjectURL(await load());
    if (tab) tab.location.href = url;
    else window.location.href = url;
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  } catch (e) {
    tab?.close();
    onError(errorText(e));
  }
}
