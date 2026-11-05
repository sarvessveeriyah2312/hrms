import { useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { can, hasModule, useAccess } from '../../lib/access';
import { Card, EmptyState, PageHeader, Tabs } from '../../components/app/kit';
import { LeaveReport } from './LeaveReport';
import { ClaimsReport } from './ClaimsReport';

type Report = 'leave' | 'claims';

/**
 * Reports, one tab per report you may see (its module switched on, and its permission in your roles). ?report=claims opens the claims report; each tab's
 * filters are in the URL too, and switching tabs starts the other report with its own defaults.
 */
export function ReportsPage() {
  const { t } = useTranslation();
  const { user } = useAccess();
  const [params, setParams] = useSearchParams();
  // A tab shows when its module is on and your role has that report ("See leave reports" / "See claims reports").
  const leaveOn = hasModule(user, 'leave') && can(user, 'reports:leave');
  const claimsOn = hasModule(user, 'claims') && can(user, 'reports:claims');
  const report: Report = (params.get('report') === 'claims' && claimsOn) || !leaveOn ? 'claims' : 'leave';

  if (!leaveOn && !claimsOn) {
    return (
      <>
        <PageHeader title={t('nav.reports')} />
        <Card>
          <EmptyState icon="chart" tone="purple" title={t('reports.noneTitle')}>{t('reports.noneBody')}</EmptyState>
        </Card>
      </>
    );
  }

  const body = report === 'claims' ? <ClaimsReport key="claims" /> : <LeaveReport key="leave" />;
  return (
    <>
      <PageHeader title={t('nav.reports')} subtitle={report === 'claims' ? t('reports.claims.subtitle') : t('reports.subtitle')} />
      {leaveOn && claimsOn ? (
        <Tabs titled
          label={t('nav.reports')}
          value={report}
          onChange={(key) => setParams(key === 'claims' ? { report: 'claims' } : {}, { replace: true })}
          tabs={[
            { key: 'leave', label: t('nav.leave'), icon: 'calendar' },
            { key: 'claims', label: t('nav.claims'), icon: 'receipt' },
          ]}
        >
          {body}
        </Tabs>
      ) : body}
    </>
  );
}
