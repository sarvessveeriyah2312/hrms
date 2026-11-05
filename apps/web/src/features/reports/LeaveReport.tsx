import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../../lib/api';
import { errorText, SCOPE_LABEL, useAccess } from '../../lib/access';
import { saveBlob } from '../../lib/download';
import { Card, StatCard, Status } from '../../components/app/kit';
import { Table } from '../../components/app/Table';
import { FilterBar } from '../../components/app/FilterBar';
import { useUrlFilters, type FilterDef } from '../../lib/filters';
import { PAGE_SIZES } from '../../lib/paging';

function n(v: number | null | undefined) {
  if (v === null || v === undefined) return '—';
  const x = Number(v);
  return Number.isInteger(x) ? String(x) : x.toFixed(1);
}

const NO_DEPT = 'none';
const BALANCE = ['over', 'low', 'unused', 'waiting'] as const;
type BalanceFilter = (typeof BALANCE)[number];
/** "Few days left" means fewer than this. */
const LOW_DAYS = 3;

/**
 * Leave report: per person and leave type, within your reports scope. Filter, read totals, export CSV.
 * Filters and rows per page are in the URL, so "Copy link" shares exactly this view.
 */
export function LeaveReport() {
  const { t } = useTranslation();
  const access = useAccess();
  const [thisYear] = useState(() => new Date().getFullYear());
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const leaveOn = access.hasModule('leave');

  const types = useQuery({ queryKey: ['leave', 'types'], queryFn: api.leave.types, enabled: leaveOn });
  // Year and type come from the server; the rest filter the rows here.
  const defs: FilterDef[] = [
    { key: 'q', label: t('common.search'), kind: 'search', pinned: true, placeholder: t('reports.searchPlaceholder') },
    { key: 'year', label: t('ent.year'), kind: 'select', pinned: true, defaultValue: String(thisYear),
      options: [thisYear - 2, thisYear - 1, thisYear, thisYear + 1].map((y) => ({ value: String(y), label: String(y) })) },
    { key: 'type', label: t('leave.form.type'), kind: 'select', pinned: true, allLabel: t('reports.allTypes'),
      options: (types.data ?? []).map((lt) => ({ value: lt.id, label: lt.name })) },
    { key: 'dept', label: t('reports.department'), kind: 'select', pinned: true, allLabel: t('reports.allDepts') },
    { key: 'balance', label: t('reports.balance'), kind: 'select', allLabel: t('reports.balanceAny'),
      options: BALANCE.map((b) => ({ value: b, label: t(`reports.balanceOpt.${b}`, { n: LOW_DAYS }) })) },
  ];
  const filters = useUrlFilters(defs);
  const year = Number(filters.values.year) || thisYear;
  const typeId = filters.values.type;
  const department = filters.values.dept;
  const words = filters.values.q.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const balance = filters.values.balance as BalanceFilter | '';
  const size = Number(filters.param('size'));
  const pageSize = PAGE_SIZES.includes(size) ? size : 25;

  const report = useQuery({
    queryKey: ['leave', 'report', year, typeId],
    queryFn: () => api.leave.report({ year, leaveTypeId: typeId || undefined }),
    enabled: leaveOn,
  });

  // Departments come from the report itself, so the list only shows ones you can see.
  const departments = useMemo(() => [...new Set((report.data?.lines ?? []).map((l) => l.department ?? NO_DEPT))].sort(), [report.data]);
  // The department choices come from the report, so they're added once it has loaded.
  const bar = {
    ...filters,
    defs: filters.defs.map((d) => (d.key === 'dept'
      ? { ...d, options: departments.map((x) => ({ value: x, label: x === NO_DEPT ? t('reports.noDept') : x })) } : d)),
  };
  const lines = (report.data?.lines ?? []).filter((l) => {
    if (department && (l.department ?? NO_DEPT) !== department) return false;
    // Every word typed must appear in the row: name, number, department, leave type or how the figure was worked out.
    const text = [l.employeeName, l.employeeNo, l.department, l.leaveType, l.basis].filter(Boolean).join(' ').toLowerCase();
    if (words.length && !words.every((w) => text.includes(w))) return false;
    const left = l.available === null ? null : Number(l.available);
    switch (balance) {
      case 'over': return left !== null && left < 0;
      case 'low': return left !== null && left >= 0 && left < LOW_DAYS;
      case 'unused': return Number(l.used) === 0;
      case 'waiting': return Number(l.pending) > 0;
      default: return true;
    }
  });
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

  return (
    <>
      {(report.isError || exportError) && <p className="form-error" role="alert">{report.isError ? errorText(report.error) : exportError}</p>}

      <FilterBar filters={bar} label={t('reports.filters')}>
        {report.data && <Status tone="blue">{SCOPE_LABEL[report.data.scope]}</Status>}
        <button type="button" className="btn small" onClick={() => void exportCsv()} disabled={exporting} title={t('reports.csvHint')}>
          {exporting ? t('reports.exporting') : t('reports.export')}
        </button>
      </FilterBar>

      <div className="grid grid--stats">
        <StatCard icon="users" label={t('nav.people')} value={people} note={department ? (department === NO_DEPT ? t('reports.noDept') : department) : t('reports.allDepts')} />
        {totals.map((tot) => (
          <StatCard key={tot.leaveTypeId} icon="calendar" label={tot.leaveType} value={n(tot.used)} suffix={t('leave.daysTaken')}
            note={t('dashboard.pendingDays', { days: n(tot.pending) })} />
        ))}
      </div>

      <Card title={t('reports.byPerson')} count={lines.length}>
        <Table
          label={t('reports.byPerson')}
          rows={lines}
          rowKey={(l) => `${l.employeeId}-${l.leaveTypeId}`}
          loading={report.isPending}
          empty={t('reports.nobody')}
          resetKey={filters.key}
          pageSize={pageSize}
          onPageSizeChange={(n) => filters.setParam('size', n === 25 ? null : String(n))}
          columns={[
            { key: 'person', header: t('reports.person'), rowHeader: true, cell: (l) => <><strong>{l.employeeName}</strong><small>{l.employeeNo}</small></> },
            { key: 'dept', header: t('reports.department'), cell: (l) => l.department ?? '—' },
            { key: 'type', header: t('leave.form.type'), cell: (l) => <>{l.leaveType}<small title={l.basis}>{l.basis}</small></> },
            { key: 'entitled', header: t('ent.entitled'), align: 'end', cell: (l) => n(l.entitled) },
            { key: 'carried', header: t('reports.carried'), align: 'end', cell: (l) => n(l.carried) },
            { key: 'used', header: t('ent.taken'), align: 'end', cell: (l) => n(l.used) },
            { key: 'pending', header: t('ent.waiting'), align: 'end', cell: (l) => n(l.pending) },
            {
              key: 'left', header: t('reports.left'), align: 'end', cell: (l) => n(l.available),
              cellClass: (l) => (l.available !== null && Number(l.available) < 0 ? 'is-negative' : undefined),
            },
          ]}
        />
      </Card>
    </>
  );
}
