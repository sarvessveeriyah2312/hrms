import { useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { can, hasModule, useAccess } from '../../lib/access';
import { Card, EmptyState, PageHeader, Tabs, type TabItem } from '../../components/app/kit';
import { LeaveReport } from './LeaveReport';
import { ClaimsReport } from './ClaimsReport';
import { AttendanceReport } from './AttendanceReport';

type Report = 'leave' | 'claims' | 'attendance';

/**
 * Reports, one tab per report you may see (its module switched on, and its permission in your roles). ?report=claims
 * or ?report=attendance opens that report; each tab's filters are in the URL too, and switching tabs starts the other
 * report with its own defaults.
 */
export function ReportsPage() {
  const { t } = useTranslation();
  const { user } = useAccess();
  const [params, setParams] = useSearchParams();
  // A tab shows when its module is on and your role has that report ("See leave / claims / attendance reports").
  const tabs: (TabItem & { key: Report })[] = [
    ...(hasModule(user, 'leave') && can(user, 'reports:leave') ? [{ key: 'leave' as const, label: t('nav.leave'), icon: 'calendar' as const }] : []),
    ...(hasModule(user, 'claims') && can(user, 'reports:claims') ? [{ key: 'claims' as const, label: t('nav.claims'), icon: 'receipt' as const }] : []),
    ...(hasModule(user, 'attendance') && can(user, 'reports:attendance') ? [{ key: 'attendance' as const, label: t('nav.attendance'), icon: 'clock' as const }] : []),
  ];

  if (tabs.length === 0) {
    return (
      <>
        <PageHeader title={t('nav.reports')} />
        <Card>
          <EmptyState icon="chart" tone="purple" title={t('reports.noneTitle')}>{t('reports.noneBody')}</EmptyState>
        </Card>
      </>
    );
  }

  const report = tabs.find((x) => x.key === params.get('report'))?.key ?? tabs[0].key;
  const body = report === 'claims' ? <ClaimsReport key="claims" />
    : report === 'attendance' ? <AttendanceReport key="attendance" /> : <LeaveReport key="leave" />;
  const subtitle = report === 'claims' ? t('reports.claims.subtitle') : report === 'attendance' ? t('reports.att.subtitle') : t('reports.subtitle');
  return (
    <>
      <PageHeader title={t('nav.reports')} subtitle={subtitle} />
      {tabs.length > 1 ? (
        <Tabs titled
          label={t('nav.reports')}
          value={report}
          onChange={(key) => setParams(key === tabs[0].key ? {} : { report: key }, { replace: true })}
          tabs={tabs}
        >
          {body}
        </Tabs>
      ) : body}
    </>
  );
}
