import { useTranslation } from 'react-i18next';
import type { PayLine } from '@hrms/api-client';
import { formatMoney } from '../../lib/money';

/** Earnings, deductions, net pay and employer contributions of a generated payslip. */
export function PayLines({ lines, currency }: { lines: PayLine[]; currency: string }) {
  const { t } = useTranslation();
  const sum = (kind: PayLine['kind']) => lines.filter((l) => l.kind === kind).reduce((n, l) => n + Number(l.amount), 0);
  const gross = sum('EARNING');
  const deductions = sum('DEDUCTION');
  const employer = sum('EMPLOYER');
  const block = (kind: PayLine['kind'], title: string, total: number, totalLabel: string) => (
    <table className="ui-table pay-lines">
      <caption>{title}</caption>
      <tbody>
        {lines.filter((l) => l.kind === kind).map((l, n) => (
          <tr key={n}>
            <th scope="row">{l.label}{l.quantity != null && l.category === 'OVERTIME' && <small>{t('payroll.lines.hoursAt', { hours: Number(l.quantity), rate: l.rate != null ? formatMoney(l.rate, currency) : '—' })}</small>}</th>
            <td className="num">{formatMoney(l.amount, currency)}</td>
          </tr>
        ))}
        <tr className="pay-lines__total"><th scope="row">{totalLabel}</th><td className="num">{formatMoney(total, currency)}</td></tr>
      </tbody>
    </table>
  );
  return (
    <div className="stack">
      {block('EARNING', t('payroll.lines.earnings'), gross, t('payroll.lines.gross'))}
      {block('DEDUCTION', t('payroll.lines.deductions'), deductions, t('payroll.lines.totalDeductions'))}
      <p className="pay-net"><span>{t('payroll.lines.net')}</span><strong>{formatMoney(gross - deductions, currency)}</strong></p>
      {employer > 0 && block('EMPLOYER', t('payroll.lines.employer'), employer, t('payroll.lines.totalEmployer'))}
    </div>
  );
}
