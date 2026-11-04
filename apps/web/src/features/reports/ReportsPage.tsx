import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../../lib/api';
import { errorText, hasModule, SCOPE_LABEL, useAccess } from '../../lib/access';
import { saveBlob } from '../../lib/download';
import { Card, EmptyState, PageHeader, StatCard, Status } from '../../components/app/kit';

function n(v: number | null | undefined) {
  if (v === null || v === undefined) return '—';
  const x = Number(v);
  return Number.isInteger(x) ? String(x) : x.toFixed(1);
}

/** Leave report: per person and leave type, within your reports scope. Filter, read totals, export CSV. */
const NO_DEPT = '\u0000none';

export function ReportsPage() {
  const { t } = useTranslation();
  const access = useAccess();
  const [thisYear] = useState(() => new Date().getFullYear());
  const [year, setYear] = useState(thisYear);
  const [typeId, setTypeId] = useState('');
  const [department, setDepartment] = useState('');
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const leaveOn = access.hasModule('leave');

  const types = useQuery({ queryKey: ['leave', 'types'], queryFn: api.leave.types, enabled: leaveOn });
  const report = useQuery({
    queryKey: ['leave', 'report', year, typeId],
    queryFn: () => api.leave.report({ year, leaveTypeId: typeId || undefined }),
    enabled: leaveOn,
  });

  // Departments come from the report itself, so the list only shows ones you can see.
  const departments = useMemo(() => [...new Set((report.data?.lines ?? []).map((l) => l.department ?? NO_DEPT))].sort(), [report.data]);
  const lines = (report.data?.lines ?? []).filter((l) => !department || (l.department ?? NO_DEPT) === department);
  const people = new Set(lines.map((l) => l.employeeId)).size;
  const totals = (report.data?.totals ?? []).map((tot) => {
    const rows = lines.filter((l) => l.leaveTypeId === tot.leaveTypeId);
    return { ...tot, used: rows.reduce((a, l) => a + Number(l.used), 0), pending: rows.reduce((a, l) => a + Number(l.pending), 0) };
  });

  const exportCsv = async () => {
    setExporting(true);
    setExportError(null);
    try {
      const blob = await api.leave.reportCsv({ year, leaveTypeId: typeId || undefined });
      saveBlob(blob, `leave-${year}${typeId ? `-${types.data?.find((lt) => lt.id === typeId)?.code.toLowerCase()}` : ''}.csv`);
    } catch (e) {
      setExportError(errorText(e));
    } finally {
      setExporting(false);
    }
  };

  if (!hasModule(access.user, 'leave')) {
    return (
      <Card>
        <EmptyState icon="chart" tone="purple" title={t('reports.noneTitle')}>{t('reports.noneBody')}</EmptyState>
      </Card>
    );
  }

  return (
    <>
      <PageHeader
        title={t('nav.reports')}
        subtitle={report.data && <><Status tone="blue">{SCOPE_LABEL[report.data.scope]}</Status>{t('reports.subtitle')}</>}
        actions={<button type="button" className="btn" onClick={() => void exportCsv()} disabled={exporting}>{exporting ? t('reports.exporting') : t('reports.export')}</button>}
      />
      {(report.isError || exportError) && <p className="form-error" role="alert">{report.isError ? errorText(report.error) : exportError}</p>}

      <div className="report-filters">
        <select className="input" value={year} onChange={(e) => setYear(Number(e.target.value))} aria-label={t('ent.year')}>
          {[thisYear - 1, thisYear, thisYear + 1].map((y) => <option key={y} value={y}>{y}</option>)}
        </select>
        <select className="input" value={typeId} onChange={(e) => setTypeId(e.target.value)} aria-label={t('leave.form.type')}>
          <option value="">{t('reports.allTypes')}</option>
          {types.data?.map((lt) => <option key={lt.id} value={lt.id}>{lt.name}</option>)}
        </select>
        <select className="input" value={department} onChange={(e) => setDepartment(e.target.value)} aria-label={t('reports.department')}>
          <option value="">{t('reports.allDepts')}</option>
          {departments.map((d) => <option key={d} value={d}>{d === NO_DEPT ? t('reports.noDept') : d}</option>)}
        </select>
        <span className="hint">{t('reports.csvHint')}</span>
      </div>

      <div className="grid grid--stats">
        <StatCard icon="users" label={t('nav.people')} value={people} note={department ? (department === NO_DEPT ? t('reports.noDept') : department) : t('reports.allDepts')} />
        {totals.map((tot) => (
          <StatCard key={tot.leaveTypeId} icon="calendar" label={tot.leaveType} value={n(tot.used)} suffix={t('leave.daysTaken')}
            note={t('dashboard.pendingDays', { days: n(tot.pending) })} />
        ))}
      </div>

      <Card title={t('reports.byPerson')} count={lines.length}>
        <div className="table-scroll">
          <table className="report-table">
            <thead>
              <tr>
                <th scope="col">{t('reports.person')}</th><th scope="col">{t('reports.department')}</th><th scope="col">{t('leave.form.type')}</th>
                <th scope="col" className="num">{t('ent.entitled')}</th><th scope="col" className="num">{t('reports.carried')}</th>
                <th scope="col" className="num">{t('ent.taken')}</th><th scope="col" className="num">{t('ent.waiting')}</th><th scope="col" className="num">{t('reports.left')}</th>
              </tr>
            </thead>
            <tbody>
              {lines.map((l) => (
                <tr key={`${l.employeeId}-${l.leaveTypeId}`}>
                  <th scope="row"><strong>{l.employeeName}</strong><small>{l.employeeNo}</small></th>
                  <td>{l.department ?? '—'}</td>
                  <td>{l.leaveType}<small title={l.basis}>{l.basis}</small></td>
                  <td className="num">{n(l.entitled)}</td>
                  <td className="num">{n(l.carried)}</td>
                  <td className="num">{n(l.used)}</td>
                  <td className="num">{n(l.pending)}</td>
                  <td className={l.available !== null && Number(l.available) < 0 ? 'num is-negative' : 'num'}>{n(l.available)}</td>
                </tr>
              ))}
              {report.data && lines.length === 0 && <tr><td colSpan={8} className="muted">{t('reports.nobody')}</td></tr>}
              {report.isPending && <tr><td colSpan={8} className="muted">{t('common.loading')}</td></tr>}
            </tbody>
          </table>
        </div>
      </Card>
    </>
  );
}
