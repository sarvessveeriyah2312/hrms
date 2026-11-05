import { useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { can, useAccess } from '../../lib/access';
import { PageHeader, Tabs, type TabItem } from '../../components/app/kit';
import { PlaceholderPage } from '../../app/PlaceholderPage';
import { TodayTab } from './TodayTab';
import { MyTimesheet, TeamTimesheets } from './TimesheetTab';

type Tab = 'today' | 'team' | 'mine';

/**
 * /app/attendance. attendance:view: Today; attendance:timesheets: Team timesheets (each within its scope); anyone with
 * an employee record: your own timesheet. The open tab is in the URL (?tab=).
 */
export function AttendancePage() {
  const { t } = useTranslation();
  const { user } = useAccess();
  const [params, setParams] = useSearchParams();
  const canView = can(user, 'attendance:view');
  const canTimesheets = can(user, 'attendance:timesheets');
  const tabs: (TabItem & { key: Tab })[] = [
    ...(canView ? [{ key: 'today' as const, label: t('attendance.tabs.today'), icon: 'clock' as const }] : []),
    ...(canTimesheets ? [{ key: 'team' as const, label: t('attendance.tabs.team'), icon: 'users' as const }] : []),
    ...(user?.employeeId ? [{ key: 'mine' as const, label: t('attendance.tabs.mine'), icon: 'calendar' as const }] : []),
  ];
  if (tabs.length === 0) return <PlaceholderPage module="attendance" />;

  const tab = tabs.find((x) => x.key === params.get('tab'))?.key ?? tabs[0].key;
  // Switching tabs drops the other tab's filters.
  const setTab = (key: string) => setParams(key === tabs[0].key ? {} : { tab: key }, { replace: true });
  const content = tab === 'today' ? <TodayTab /> : tab === 'team' ? <TeamTimesheets /> : <MyTimesheet />;

  return (
    <>
      <PageHeader title={t('nav.attendance')} subtitle={t(`attendance.subtitle.${tab}`)} />
      {tabs.length > 1
        ? <Tabs titled label={t('nav.attendance')} value={tab} onChange={setTab} tabs={tabs}>{content}</Tabs>
        : content}
    </>
  );
}
