import { useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { PayrollDashboardMonth } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { formatMoney, formatMonth } from '../../lib/money';
import { Card, List, Row, StatCard } from '../../components/app/kit';
import { Table } from '../../components/app/Table';

const currentYear = () => new Date().getFullYear();

const money = (m: Record<string, number>) => {
  const entries = Object.entries(m);
  return entries.length ? entries.map(([c, v]) => formatMoney(v, c)).join(' · ') : '—';
};

/** Head count, joiners and leavers, payroll cost (from payslips with amounts), claims and overtime by month. */
export function PayrollDashboardTab() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const thisYear = currentYear();
  const year = Number(params.get('year')) || thisYear;
  const data = useQuery({ queryKey: ['payroll', 'dashboard', year], queryFn: () => api.payroll.dashboard(year) });
  const d = data.data;
  const peak = Math.max(1, ...(d?.months.map((m) => m.headcount) ?? [1]));

  return (
    <div className="stack">
      <div className="payroll-toolbar">
        <label className="field">
          {t('payroll.period.year')}
          <select value={year} onChange={(e) => setParams({ tab: 'dashboard', year: e.target.value }, { replace: true })}>
            {Array.from({ length: 6 }, (_, i) => thisYear - i).map((y) => <option key={y} value={y}>{y}</option>)}
          </select>
        </label>
      </div>
      {data.isError && <p className="form-error" role="alert">{errorText(data.error)}</p>}

      <div className="grid grid--stats">
        <StatCard icon="users" label={t('payroll.dash.headcount')} value={d ? d.headcountNow : '–'} note={t('payroll.dash.headcountNote')} />
        <StatCard icon="plus" label={t('payroll.dash.joiners')} value={d ? d.joiners : '–'} note={t('payroll.dash.inYear', { year })} />
        <StatCard icon="logout" label={t('payroll.dash.leavers')} value={d ? d.leavers : '–'}
          note={d?.turnover != null ? t('payroll.dash.turnover', { rate: d.turnover }) : t('payroll.dash.inYear', { year })} />
        <StatCard icon="wallet" label={t('payroll.dash.cost')} value={d ? money(d.costThisYear) : '–'} note={t('payroll.dash.costNote')} />
      </div>

      <div className="grid grid--main">
        <Card title={t('payroll.dash.byMonth')}>
          <Table<PayrollDashboardMonth>
            rows={d?.months ?? []}
            rowKey={(m) => m.month}
            label={t('payroll.dash.byMonth')}
            loading={data.isPending}
            empty={t('payroll.dash.noMonths')}
            pageSizes={[]}
            pageSize={12}
            columns={[
              { key: 'month', header: t('payroll.dash.month'), rowHeader: true, cell: (m) => formatMonth(m.month) },
              { key: 'headcount', header: t('payroll.dash.headcountCol'), align: 'end',
                cell: (m) => (
                  <span className="payroll-bar-cell">
                    <span className="payroll-bar" aria-hidden="true" style={{ width: `${Math.round((m.headcount / peak) * 100)}%` }} />
                    <span>{m.headcount}</span>
                  </span>
                ) },
              { key: 'joiners', header: t('payroll.dash.joinersCol'), align: 'end', cell: (m) => (m.joiners ? `+${m.joiners}` : '0') },
              { key: 'leavers', header: t('payroll.dash.leaversCol'), align: 'end', cell: (m) => (m.leavers ? `−${m.leavers}` : '0') },
              { key: 'cost', header: t('payroll.dash.costCol'), align: 'end',
                cell: (m) => <>{money(m.cost)}{m.payslips > 0 && <small>{t('payroll.dash.payslips', { count: m.payslips })}</small>}</> },
              { key: 'claims', header: t('payroll.dash.claimsCol'), align: 'end', cell: (m) => money(m.claims) },
              { key: 'ot', header: t('payroll.dash.overtimeCol'), align: 'end', cell: (m) => Number(m.overtimeHours).toFixed(1).replace(/\.0$/, '') },
            ]}
          />
        </Card>
        <Card title={t('payroll.dash.departments')} count={d?.departments.length}>
          <List>
            {d?.departments.map((x) => <Row key={x.name} title={x.name} trail={<strong className="num">{x.headcount}</strong>} />)}
            {data.isPending && <Row title={t('common.loading')} />}
          </List>
        </Card>
      </div>
      <p className="hint">{t('payroll.dash.footnote')}</p>
    </div>
  );
}
