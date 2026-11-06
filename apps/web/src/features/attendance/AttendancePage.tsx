import { useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { can, useAccess } from '../../lib/access';
import { PageHeader, Tabs, type TabItem } from '../../components/app/kit';
import { PlaceholderPage } from '../../app/PlaceholderPage';
import { TodayTab } from './TodayTab';
import { MyTimesheet, TeamTimesheets } from './TimesheetTab';
import { MyOvertime } from './Overtime';
import { MySchedule } from './MySchedule';

type Tab = 'today' | 'team' | 'mine' | 'schedule' | 'overtime';

/**
 * /app/attendance. attendance:view: Today; attendance:timesheets: Team timesheets (each within its scope); anyone with
 * an employee record: your own timesheet; attendance:clock with an employee record: your overtime requests. The open tab
 * is in the URL (?tab=).
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
    ...(user?.employeeId && can(user, 'attendance:clock') ? [{ key: 'schedule' as const, label: t('attendance.tabs.schedule'), icon: 'clock' as const }] : []),
    ...(user?.employeeId && can(user, 'attendance:clock') ? [{ key: 'overtime' as const, label: t('attendance.tabs.overtime'), icon: 'star' as const }] : []),
  ];
  if (tabs.length === 0) return <PlaceholderPage module="attendance" />;

  const tab = tabs.find((x) => x.key === params.get('tab'))?.key ?? tabs[0].key;
  // Switching tabs drops the other tab's filters.
  const setTab = (key: string) => setParams(key === tabs[0].key ? {} : { tab: key }, { replace: true });
  const content = tab === 'today' ? <TodayTab /> : tab === 'team' ? <TeamTimesheets />
    : tab === 'overtime' ? <MyOvertime /> : tab === 'schedule' ? <MySchedule /> : <MyTimesheet />;

  return (
    <>
      <PageHeader title={t('nav.attendance')} subtitle={t(`attendance.subtitle.${tab}`)} />
      {tabs.length > 1
        ? <Tabs titled label={t('nav.attendance')} value={tab} onChange={setTab} tabs={tabs}>{content}</Tabs>
        : content}
    </>
  );
}
