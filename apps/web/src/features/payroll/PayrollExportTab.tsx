import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { PayrollExportRow } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { saveBlob } from '../../lib/download';
import { formatMoney, formatMonth } from '../../lib/money';
import { Card, Status } from '../../components/app/kit';
import { Table, type Column } from '../../components/app/Table';

function lastMonth() {
  const d = new Date();
  d.setDate(1);
  d.setMonth(d.getMonth() - 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

const hours = (n: number) => (Number(n) === 0 ? '0' : Number(n).toFixed(2).replace(/\.?0+$/, ''));

/**
 * A pay period for payroll: per person, attendance (timesheet rules), approved overtime by rate and approved claims.
 * Warns while the month isn't locked or requests for it are still waiting, since those can still change it.
 */
export function PayrollExportTab() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const month = params.get('month') ?? lastMonth();
  const preview = useQuery({ queryKey: ['payroll', 'export', month], queryFn: () => api.payroll.exportPreview(month), enabled: /^\d{4}-\d{2}$/.test(month) });
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const s = preview.data;
  const waiting = s ? s.waiting.corrections + s.waiting.overtime + s.waiting.claims : 0;

  const download = async () => {
    setDownloading(true);
    setError(null);
    try {
      saveBlob(await api.payroll.exportCsv(month), `payroll-${month}.csv`);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setDownloading(false);
    }
  };

  const columns: Column<PayrollExportRow>[] = [
    { key: 'person', header: t('payroll.col.person'), rowHeader: true,
      cell: (r) => <>{r.employeeName}<small>{[r.employeeNo, r.department].filter(Boolean).join(' · ')}</small></> },
    { key: 'days', header: t('payroll.export.days'), align: 'end', cell: (r) => `${r.daysWorked} / ${r.workingDays}` },
    { key: 'hours', header: t('payroll.export.hours'), align: 'end', cell: (r) => hours(r.hoursWorked) },
    { key: 'late', header: t('payroll.export.late'), align: 'end', cell: (r) => r.lateCount },
    { key: 'absent', header: t('payroll.export.absent'), align: 'end', cell: (r) => r.absentDays },
    { key: 'leave', header: t('payroll.export.leave'), align: 'end', cell: (r) => hours(r.leaveDays) },
    { key: 'ot', header: t('payroll.export.overtime'), align: 'end',
      cell: (r) => <>{hours(r.paidOvertimeHours)}{Number(r.weightedOvertimeHours) > 0 && <small>{t('payroll.export.weighted', { hours: hours(r.weightedOvertimeHours) })}</small>}</> },
    ...(s?.currencies ?? []).map((c): Column<PayrollExportRow> => ({
      key: `claims-${c}`, header: t('payroll.export.claims', { currency: c }), align: 'end',
      cell: (r) => r.claims[c] ? <>{formatMoney(r.claims[c], c)}{r.claimsUnpaid[c] ? <small>{t('payroll.export.unpaid', { amount: formatMoney(r.claimsUnpaid[c], c) })}</small> : null}</> : '—',
    })),
  ];

  return (
    <div className="stack">
      <div className="payroll-toolbar">
        <label className="field">
          {t('payroll.period.month')}
          <input type="month" value={month} onChange={(e) => e.target.value && setParams({ tab: 'export', month: e.target.value }, { replace: true })} />
        </label>
        <div className="panel-actions payroll-toolbar__actions">
          <button type="button" className="btn primary" onClick={() => void download()} disabled={downloading || !s}>
            {downloading ? t('payroll.export.downloading') : t('payroll.export.download')}
          </button>
        </div>
      </div>
      {preview.isError && <p className="form-error" role="alert">{errorText(preview.error)}</p>}
      {error && <p className="form-error" role="alert">{error}</p>}
      {s && (
        <div className="payroll-checks" role="status">
          {s.locked
            ? <Status tone="green">{t('payroll.export.locked')}</Status>
            : <span><Status tone="amber">{t('payroll.export.notLocked')}</Status> <Link to={`/app/attendance?tab=team&month=${month}`}>{t('payroll.export.lockIt')}</Link></span>}
          {waiting > 0
            ? <Status tone="amber">{t('payroll.export.waiting', { corrections: s.waiting.corrections, overtime: s.waiting.overtime, claims: s.waiting.claims })}</Status>
            : <Status tone="green">{t('payroll.export.nothingWaiting')}</Status>}
          {s.truncated && <Status tone="red">{t('payroll.export.truncated')}</Status>}
        </div>
      )}
      <Card title={t('payroll.export.title', { month: formatMonth(month) })} count={s?.rows.length}>
        <Table rows={s?.rows ?? []} rowKey={(r) => r.employeeId} label={t('payroll.export.title', { month: formatMonth(month) })}
          loading={preview.isPending} empty={t('payroll.export.empty')} resetKey={month} columns={columns} />
      </Card>
      <p className="hint">{t('payroll.export.csvNote')}</p>
    </div>
  );
}
