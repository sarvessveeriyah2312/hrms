import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { ClaimsReportRow } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText, SCOPE_LABEL } from '../../lib/access';
import { monthName } from '../../lib/dates';
import { saveBlob } from '../../lib/download';
import { useUrlFilters, type FilterDef } from '../../lib/filters';
import { formatMoney } from '../../lib/money';
import { PAGE_SIZES, urlPageSize } from '../../lib/paging';
import { Card, StatCard, Status } from '../../components/app/kit';
import { FilterBar } from '../../components/app/FilterBar';
import { Table } from '../../components/app/Table';

const NO_DEPT = 'none';
const SHOW = ['toPay', 'waiting', 'paid'] as const;
type Show = (typeof SHOW)[number];

type Sums = { claims: number; approved: number; pending: number; paid: number; toPay: number };
const zero = (): Sums => ({ claims: 0, approved: 0, pending: 0, paid: 0, toPay: 0 });
function add(into: Sums, r: Sums) {
  into.claims += Number(r.claims);
  into.approved += Number(r.approved);
  into.pending += Number(r.pending);
  into.paid += Number(r.paid);
  into.toPay += Number(r.toPay);
}

/** Sums grouped by a key, keeping the first row's fields for labels. */
function rollUp<K extends string>(rows: ClaimsReportRow[], key: (r: ClaimsReportRow) => K) {
  const out = new Map<K, { first: ClaimsReportRow; sums: Sums; people: Set<string> }>();
  for (const r of rows) {
    const k = key(r);
    const g = out.get(k) ?? { first: r, sums: zero(), people: new Set<string>() };
    add(g.sums, r);
    g.people.add(r.employeeId);
    out.set(k, g);
  }
  return [...out.values()];
}

/** "RM 1,240.00" or "RM 1,240.00 + S$310.00" when claimants are paid in different currencies. */
function perCurrency(rows: ClaimsReportRow[], field: keyof Omit<Sums, 'claims'>) {
  const by = new Map<string, number>();
  rows.forEach((r) => by.set(r.currency, (by.get(r.currency) ?? 0) + Number(r[field])));
  const parts = [...by].filter(([, n]) => n > 0).map(([cur, n]) => formatMoney(n, cur));
  return parts.length ? parts.join(' + ') : formatMoney(0, rows[0]?.currency ?? 'MYR');
}

/**
 * Claims report: approved and waiting claims by expense date, by person, claim type and month, within your
 * reports scope. Year and month come from the server; the other filters work on the rows here, so every total
 * on the page follows them. Filters and rows per page are in the URL ("Copy link" shares the view).
 */
export function ClaimsReport() {
  const { t } = useTranslation();
  const [thisYear] = useState(() => new Date().getFullYear());
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);

  const defs: FilterDef[] = [
    { key: 'q', label: t('common.search'), kind: 'search', pinned: true, placeholder: t('reports.claims.searchPlaceholder') },
    { key: 'year', label: t('ent.year'), kind: 'select', pinned: true, defaultValue: String(thisYear),
      options: [thisYear - 2, thisYear - 1, thisYear].map((y) => ({ value: String(y), label: String(y) })) },
    { key: 'month', label: t('reports.claims.month'), kind: 'select', pinned: true, allLabel: t('reports.claims.wholeYear'),
      options: Array.from({ length: 12 }, (_, i) => ({ value: String(i + 1), label: monthName(i + 1) })) },
    { key: 'type', label: t('claims.form.type'), kind: 'select', pinned: true, allLabel: t('fb.allClaimTypes') },
    { key: 'dept', label: t('reports.department'), kind: 'select', pinned: true, allLabel: t('reports.allDepts') },
    { key: 'show', label: t('reports.claims.show'), kind: 'select', allLabel: t('reports.claims.showAny'),
      options: SHOW.map((s) => ({ value: s, label: t(`reports.claims.showOpt.${s}`) })) },
  ];
  const filters = useUrlFilters(defs);
  const year = Number(filters.values.year) || thisYear;
  const month = Number(filters.values.month) || undefined;
  const typeId = filters.values.type;
  const department = filters.values.dept;
  const show = filters.values.show as Show | '';
  const words = filters.values.q.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const size = Number(filters.param('size'));
  const pageSize = PAGE_SIZES.includes(size) ? size : 25;

  const report = useQuery({ queryKey: ['claims', 'report', year, month], queryFn: () => api.claims.report({ year, month }) });
  const all = useMemo(() => report.data?.rows ?? [], [report.data]);

  // Type and department choices come from the report, so they only list what you can see.
  const typeOptions = useMemo(() => rollUp(all, (r) => r.claimTypeId).map((g) => ({ value: g.first.claimTypeId, label: g.first.claimType })), [all]);
  const deptOptions = useMemo(() => [...new Set(all.map((r) => r.department ?? NO_DEPT))].sort(), [all]);
  const bar = {
    ...filters,
    defs: filters.defs.map((d) => (d.key === 'type' ? { ...d, options: typeOptions }
      : d.key === 'dept' ? { ...d, options: deptOptions.map((x) => ({ value: x, label: x === NO_DEPT ? t('reports.noDept') : x })) } : d)),
  };

  const rows = all.filter((r) => {
    if (typeId && r.claimTypeId !== typeId) return false;
    if (department && (r.department ?? NO_DEPT) !== department) return false;
    const text = [r.employeeName, r.employeeNo, r.department, r.claimType].filter(Boolean).join(' ').toLowerCase();
    if (words.length && !words.every((w) => text.includes(w))) return false;
    if (show === 'toPay') return Number(r.toPay) > 0;
    if (show === 'waiting') return Number(r.pending) > 0;
    if (show === 'paid') return Number(r.paid) > 0;
    return true;
  });

  const byPerson = rollUp(rows, (r) => `${r.employeeId}|${r.claimTypeId}|${r.currency}`);
  const byType = rollUp(rows, (r) => `${r.claimTypeId}|${r.currency}`).sort((a, b) => b.sums.approved - a.sums.approved);
  const byMonth = rollUp(rows, (r) => `${String(r.month).padStart(2, '0')}|${r.currency}`)
    .sort((a, b) => a.first.month - b.first.month || a.first.currency.localeCompare(b.first.currency));
  const people = new Set(rows.map((r) => r.employeeId)).size;
  const claims = rows.reduce((n, r) => n + Number(r.claims), 0);

  const exportCsv = async () => {
    setExporting(true);
    setExportError(null);
    try {
      const blob = await api.claims.reportCsv({ year, month, claimTypeId: typeId || undefined });
      saveBlob(blob, `claims-${year}${month ? `-${String(month).padStart(2, '0')}` : ''}.csv`);
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
        <button type="button" className="btn small" onClick={() => void exportCsv()} disabled={exporting} title={t('reports.claims.csvHint')}>
          {exporting ? t('reports.exporting') : t('reports.export')}
        </button>
      </FilterBar>

      <div className="grid grid--stats">
        <StatCard icon="check" label={t('reports.claims.approved')} value={perCurrency(rows, 'approved')}
          note={`${t('claims.stats.nClaims', { count: claims })} · ${t('common.person', { count: people })}`} />
        <StatCard icon="clock" label={t('claims.stats.waiting')} value={perCurrency(rows, 'pending')} note={t('reports.claims.waitingNote')} />
        <StatCard icon="receipt" label={t('claims.stats.toBePaid')} value={perCurrency(rows, 'toPay')} note={t('reports.claims.toPayNote')} />
        <StatCard icon="chart" label={t('claims.paid')} value={perCurrency(rows, 'paid')}
          note={month ? `${monthName(month)} ${year}` : String(year)} />
      </div>

      <div className="grid grid--main">
        <Card title={t('reports.claims.byPerson')} count={byPerson.length}>
          <Table
            label={t('reports.claims.byPerson')}
            rows={byPerson}
            rowKey={(g) => `${g.first.employeeId}|${g.first.claimTypeId}|${g.first.currency}`}
            loading={report.isPending}
            empty={t('reports.nobody')}
            resetKey={filters.key}
            pageSize={pageSize}
            onPageSizeChange={(n) => filters.setParam('size', n === 25 ? null : String(n))}
            columns={[
              { key: 'person', header: t('reports.person'), rowHeader: true, cell: (g) => <><strong>{g.first.employeeName}</strong><small>{g.first.employeeNo}</small></> },
              { key: 'dept', header: t('reports.department'), cell: (g) => g.first.department ?? '—' },
              { key: 'type', header: t('claims.form.type'), cell: (g) => g.first.claimType },
              { key: 'claims', header: t('reports.claims.claims'), align: 'end', cell: (g) => g.sums.claims },
              { key: 'approved', header: t('reports.claims.approved'), align: 'end', cell: (g) => formatMoney(g.sums.approved, g.first.currency) },
              { key: 'pending', header: t('ent.waiting'), align: 'end', cell: (g) => (g.sums.pending ? formatMoney(g.sums.pending, g.first.currency) : '—') },
              { key: 'toPay', header: t('reports.claims.toPay'), align: 'end', cell: (g) => (g.sums.toPay ? formatMoney(g.sums.toPay, g.first.currency) : '—'),
                cellClass: (g) => (g.sums.toPay > 0 ? 'is-due' : undefined) },
              { key: 'paid', header: t('claims.paid'), align: 'end', cell: (g) => (g.sums.paid ? formatMoney(g.sums.paid, g.first.currency) : '—') },
            ]}
          />
        </Card>

        <div className="col">
          <Card title={t('reports.claims.byType')} count={byType.length}>
            <Table
              label={t('reports.claims.byType')}
              rows={byType}
              rowKey={(g) => `${g.first.claimTypeId}|${g.first.currency}`}
              loading={report.isPending}
              empty={t('reports.claims.none')}
              resetKey={filters.key}
              {...urlPageSize(filters, 'typeSize', 10)}
              columns={[
                { key: 'type', header: t('claims.form.type'), rowHeader: true,
                  cell: (g) => <>{g.first.claimType}<small>{t('reports.claims.typeMeta', { claims: g.sums.claims, people: g.people.size })}</small></> },
                { key: 'approved', header: t('reports.claims.approved'), align: 'end', cell: (g) => formatMoney(g.sums.approved, g.first.currency) },
                { key: 'pending', header: t('ent.waiting'), align: 'end', cell: (g) => (g.sums.pending ? formatMoney(g.sums.pending, g.first.currency) : '—') },
              ]}
            />
          </Card>

          <Card title={t('reports.claims.byMonth')} count={month ? undefined : byMonth.length}>
            <Table
              label={t('reports.claims.byMonth')}
              rows={byMonth}
              rowKey={(g) => `${g.first.month}|${g.first.currency}`}
              loading={report.isPending}
              empty={t('reports.claims.none')}
              resetKey={filters.key}
              {...urlPageSize(filters, 'monthSize', 10)}
              columns={[
                { key: 'month', header: t('reports.claims.month'), rowHeader: true, cell: (g) => monthName(g.first.month) },
                { key: 'claims', header: t('reports.claims.claims'), align: 'end', cell: (g) => g.sums.claims },
                { key: 'approved', header: t('reports.claims.approved'), align: 'end', cell: (g) => formatMoney(g.sums.approved, g.first.currency) },
                { key: 'pending', header: t('ent.waiting'), align: 'end', cell: (g) => (g.sums.pending ? formatMoney(g.sums.pending, g.first.currency) : '—') },
              ]}
            />
          </Card>
        </div>
      </div>
    </>
  );
}
