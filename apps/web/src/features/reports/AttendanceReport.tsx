import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import type { AttendanceReportRow } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText, SCOPE_LABEL } from '../../lib/access';
import { monthName } from '../../lib/dates';
import { saveBlob } from '../../lib/download';
import { useUrlFilters, type FilterDef } from '../../lib/filters';
import { PAGE_SIZES, urlPageSize } from '../../lib/paging';
import { Card, StatCard, Status } from '../../components/app/kit';
import { FilterBar } from '../../components/app/FilterBar';
import { Table } from '../../components/app/Table';

const NO_DEPT = 'none';
const SHOW = ['late', 'early', 'missed', 'absent', 'overtime'] as const;
type Show = (typeof SHOW)[number];

/** Everything the report adds up, per person or per month. */
type Sums = {
  expected: number; worked: number; workedMin: number; lateCount: number; lateMin: number; earlyCount: number; earlyMin: number;
  missed: number; absent: number; leave: number; paidMin: number; weightedMin: number; timeOffMin: number;
};
const zero = (): Sums => ({
  expected: 0, worked: 0, workedMin: 0, lateCount: 0, lateMin: 0, earlyCount: 0, earlyMin: 0,
  missed: 0, absent: 0, leave: 0, paidMin: 0, weightedMin: 0, timeOffMin: 0,
});
function add(s: Sums, r: AttendanceReportRow) {
  const x = r.totals;
  s.expected += x.expectedDays; s.worked += x.daysWorked; s.workedMin += x.workedMin;
  s.lateCount += x.lateCount; s.lateMin += x.lateMin; s.earlyCount += x.earlyCount; s.earlyMin += x.earlyMin;
  s.missed += x.missedOut; s.absent += x.absent; s.leave += Number(x.leaveDays);
  s.paidMin += r.overtimePaidMin; s.weightedMin += r.weightedMin; s.timeOffMin += r.overtimeTimeOffMin;
}

function rollUp(rows: AttendanceReportRow[], key: (r: AttendanceReportRow) => string) {
  const out = new Map<string, { first: AttendanceReportRow; sums: Sums; people: Set<string>; locked: boolean }>();
  for (const r of rows) {
    const k = key(r);
    const g = out.get(k) ?? { first: r, sums: zero(), people: new Set<string>(), locked: r.locked };
    add(g.sums, r);
    g.people.add(r.employeeId);
    out.set(k, g);
  }
  return [...out.values()];
}

/** "7h 30m"; "—" for nothing. */
function hm(t: TFunction, minutes: number) {
  if (minutes <= 0) return '—';
  return t('timesheet.hm', { h: Math.floor(minutes / 60), m: String(minutes % 60).padStart(2, '0') });
}

/** "3 · 45 min" */
function countMin(t: TFunction, count: number, minutes: number) {
  return count ? t('timesheet.countTime', { n: count, time: hm(t, minutes) }) : '—';
}

/**
 * Attendance report: per person and month (a year, or one month of it), within your "See attendance reports" scope.
 * Hours, lateness, early leaving, missed clock-outs and absence follow the timesheet rules; overtime is approved
 * overtime, split into paid (and paid hours × rate) and time off. Year and month come from the server; the other
 * filters work here, so every total follows them. CSV has a line per person and month, with a column per rate.
 */
export function AttendanceReport() {
  const { t } = useTranslation();
  const [thisYear] = useState(() => new Date().getFullYear());
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);

  const defs: FilterDef[] = [
    { key: 'q', label: t('common.search'), kind: 'search', pinned: true, placeholder: t('reports.att.searchPlaceholder') },
    { key: 'year', label: t('ent.year'), kind: 'select', pinned: true, defaultValue: String(thisYear),
      options: [thisYear - 2, thisYear - 1, thisYear].map((y) => ({ value: String(y), label: String(y) })) },
    { key: 'month', label: t('reports.claims.month'), kind: 'select', pinned: true, allLabel: t('reports.claims.wholeYear'),
      options: Array.from({ length: 12 }, (_, i) => ({ value: String(i + 1), label: monthName(i + 1) })) },
    { key: 'dept', label: t('reports.department'), kind: 'select', pinned: true, allLabel: t('reports.allDepts') },
    { key: 'site', label: t('reports.att.site'), kind: 'select', allLabel: t('reports.att.allSites') },
    { key: 'show', label: t('reports.claims.show'), kind: 'select', allLabel: t('reports.claims.showAny'),
      options: SHOW.map((s) => ({ value: s, label: t(`reports.att.showOpt.${s}`) })) },
  ];
  const filters = useUrlFilters(defs);
  const year = Number(filters.values.year) || thisYear;
  const month = Number(filters.values.month) || undefined;
  const department = filters.values.dept;
  const site = filters.values.site;
  const show = filters.values.show as Show | '';
  const words = filters.values.q.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const size = Number(filters.param('size'));
  const pageSize = PAGE_SIZES.includes(size) ? size : 25;

  const report = useQuery({ queryKey: ['attendance', 'report', year, month], queryFn: () => api.attendance.report({ year, month }) });
  const all = useMemo(() => report.data?.rows ?? [], [report.data]);

  const deptOptions = useMemo(() => [...new Set(all.map((r) => r.department ?? NO_DEPT))].sort(), [all]);
  const siteOptions = useMemo(() => [...new Set(all.map((r) => r.site).filter((x): x is string => !!x))].sort(), [all]);
  const bar = {
    ...filters,
    defs: filters.defs.map((d) => (d.key === 'dept' ? { ...d, options: deptOptions.map((x) => ({ value: x, label: x === NO_DEPT ? t('reports.noDept') : x })) }
      : d.key === 'site' ? { ...d, options: siteOptions.map((x) => ({ value: x, label: x })) } : d)),
  };

  const rows = all.filter((r) => {
    if (department && (r.department ?? NO_DEPT) !== department) return false;
    if (site && r.site !== site) return false;
    const text = [r.employeeName, r.employeeNo, r.department, r.site].filter(Boolean).join(' ').toLowerCase();
    if (words.length && !words.every((w) => text.includes(w))) return false;
    const x = r.totals;
    if (show === 'late') return x.lateCount > 0;
    if (show === 'early') return x.earlyCount > 0;
    if (show === 'missed') return x.missedOut > 0;
    if (show === 'absent') return x.absent > 0;
    if (show === 'overtime') return r.overtimePaidMin + r.overtimeTimeOffMin > 0;
    return true;
  });

  const byPerson = rollUp(rows, (r) => r.employeeId);
  const byMonth = rollUp(rows, (r) => r.month).sort((a, b) => a.first.month.localeCompare(b.first.month));
  const total = zero();
  rows.forEach((r) => add(total, r));
  const people = byPerson.length;
  const lockedMonths = byMonth.filter((g) => g.locked).length;

  const exportCsv = async () => {
    setExporting(true);
    setExportError(null);
    try {
      const blob = await api.attendance.reportCsv({ year, month });
      saveBlob(blob, `attendance-${year}${month ? `-${String(month).padStart(2, '0')}` : ''}.csv`);
    } catch (e) {
      setExportError(errorText(e));
    } finally {
      setExporting(false);
    }
  };

  return (
    <>
      {(report.isError || exportError) && <p className="form-error" role="alert">{report.isError ? errorText(report.error) : exportError}</p>}
      {report.data?.truncated && <p className="notice" role="status">{t('reports.att.truncated')}</p>}

      <FilterBar filters={bar} label={t('reports.filters')}>
        {report.data && <Status tone="blue">{SCOPE_LABEL[report.data.scope]}</Status>}
        <button type="button" className="btn small" onClick={() => void exportCsv()} disabled={exporting} title={t('reports.att.csvHint')}>
          {exporting ? t('reports.exporting') : t('reports.export')}
        </button>
      </FilterBar>

      <div className="grid grid--stats">
        <StatCard icon="clock" label={t('timesheet.hoursWorked')} value={report.data ? hm(t, total.workedMin) : '–'}
          note={t('common.person', { count: people })} />
        <StatCard icon="users" label={t('timesheet.late')} value={report.data ? total.lateCount : '–'}
          note={t('timesheet.timesTotal', { time: hm(t, total.lateMin) })} />
        <StatCard icon="calendar" label={t('timesheet.absent')} value={report.data ? total.absent : '–'}
          note={t('timesheet.missedOutN', { count: total.missed })} />
        <StatCard icon="star" label={t('reports.att.overtimePaid')} value={report.data ? hm(t, total.paidMin) : '–'}
          note={[t('reports.att.payHours', { time: hm(t, total.weightedMin) }), total.timeOffMin > 0 && t('reports.att.timeOffNote', { time: hm(t, total.timeOffMin) })]
            .filter(Boolean).join(' · ')} />
      </div>

      <Card title={t('reports.att.byPerson')} count={byPerson.length}>
        <Table
          label={t('reports.att.byPerson')}
          rows={byPerson}
          rowKey={(g) => g.first.employeeId}
          loading={report.isPending}
          empty={t('reports.nobody')}
          resetKey={filters.key}
          pageSize={pageSize}
          onPageSizeChange={(n) => filters.setParam('size', n === 25 ? null : String(n))}
          columns={[
            { key: 'person', header: t('reports.person'), rowHeader: true, cell: (g) => <><strong>{g.first.employeeName}</strong><small>{[g.first.employeeNo, g.first.department].filter(Boolean).join(' · ')}</small></> },
            { key: 'days', header: t('timesheet.daysWorked'), align: 'end', cell: (g) => t('timesheet.ofDays', { n: g.sums.worked, of: g.sums.expected }) },
            { key: 'hours', header: t('timesheet.hours'), align: 'end', cell: (g) => hm(t, g.sums.workedMin) },
            { key: 'late', header: t('timesheet.late'), align: 'end', cell: (g) => countMin(t, g.sums.lateCount, g.sums.lateMin),
              cellClass: (g) => (g.sums.lateCount > 0 ? 'is-warning' : undefined) },
            { key: 'early', header: t('timesheet.leftEarly'), align: 'end', cell: (g) => countMin(t, g.sums.earlyCount, g.sums.earlyMin),
              cellClass: (g) => (g.sums.earlyCount > 0 ? 'is-warning' : undefined) },
            { key: 'missed', header: t('timesheet.missedOut'), align: 'end', cell: (g) => g.sums.missed || '—' },
            { key: 'absent', header: t('timesheet.absent'), align: 'end', cell: (g) => g.sums.absent || '—',
              cellClass: (g) => (g.sums.absent > 0 ? 'is-negative' : undefined) },
            { key: 'leave', header: t('timesheet.leave'), align: 'end', cell: (g) => g.sums.leave || '—' },
            { key: 'ot', header: t('reports.att.otPaid'), align: 'end', cell: (g) => hm(t, g.sums.paidMin) },
            { key: 'pay', header: t('reports.att.otPayHours'), align: 'end', cell: (g) => hm(t, g.sums.weightedMin) },
            { key: 'off', header: t('reports.att.otTimeOff'), align: 'end', cell: (g) => hm(t, g.sums.timeOffMin) },
          ]}
        />
      </Card>

      {!month && (
        <Card title={t('reports.att.byMonth')} count={byMonth.length}
          action={lockedMonths > 0 && <Status tone="grey">{t('reports.att.lockedMonths', { count: lockedMonths })}</Status>}>
          <Table
            label={t('reports.att.byMonth')}
            rows={byMonth}
            rowKey={(g) => g.first.month}
            loading={report.isPending}
            empty={t('reports.nobody')}
            resetKey={filters.key}
            {...urlPageSize(filters, 'monthSize', 10)}
            columns={[
              { key: 'month', header: t('reports.claims.month'), rowHeader: true, cell: (g) => (
                <>{monthName(Number(g.first.month.slice(5)))}{g.locked && <> <Status tone="grey">{t('lock.locked')}</Status></>}</>
              ) },
              { key: 'people', header: t('reports.att.people'), align: 'end', cell: (g) => g.people.size },
              { key: 'hours', header: t('timesheet.hours'), align: 'end', cell: (g) => hm(t, g.sums.workedMin) },
              { key: 'late', header: t('timesheet.late'), align: 'end', cell: (g) => g.sums.lateCount || '—' },
              { key: 'absent', header: t('timesheet.absent'), align: 'end', cell: (g) => g.sums.absent || '—' },
              { key: 'ot', header: t('reports.att.otPaid'), align: 'end', cell: (g) => hm(t, g.sums.paidMin) },
              { key: 'pay', header: t('reports.att.otPayHours'), align: 'end', cell: (g) => hm(t, g.sums.weightedMin) },
              { key: 'off', header: t('reports.att.otTimeOff'), align: 'end', cell: (g) => hm(t, g.sums.timeOffMin) },
            ]}
          />
        </Card>
      )}
    </>
  );
}
