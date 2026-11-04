import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../../lib/api';
import { can, canAny, hasModule, SCOPE_LABEL, useAccess } from '../../lib/access';
import { formatDate, formatRange, isoDate, relative, toTone } from '../../lib/dates';
import { MODULE_META, moduleMeta } from '../../lib/modules';
import { Glyph } from '../../components/Glyph';
import { Avatar, Card, CardLink, List, PageHeader, Row, StatCard, Status, Tile } from '../../components/app/kit';
import { useInbox } from '../approvals/useInbox';

function greetingKey(date: Date) {
  const h = date.getHours();
  return h < 12 ? 'dashboard.morning' : h < 18 ? 'dashboard.afternoon' : 'dashboard.evening';
}

/** Home, laid out like the design reference. Everything here is live data. */
export function DashboardPage() {
  const { t } = useTranslation();
  const { user } = useAccess();
  const [now] = useState(() => new Date());
  const today = isoDate(now);
  const leaveOn = hasModule(user, 'leave');
  const approver = canAny(user, ['attendance:approve', 'leave:approve', 'claims:approve']);
  const canSeePeople = can(user, 'employee:view');
  const seesTeamLeave = leaveOn && can(user, 'leave:view', 'TEAM');
  const hasLeave = leaveOn && can(user, 'leave:apply') && !!user?.employeeId;

  const inbox = useInbox(approver);
  const people = useQuery({ queryKey: ['employees', ''], queryFn: () => api.employees(), enabled: canSeePeople });
  const off = useQuery({ queryKey: ['leave', 'calendar', today, today], queryFn: () => api.leave.calendar(today, today), enabled: seesTeamLeave });
  const balances = useQuery({ queryKey: ['leave', 'balances', 'me'], queryFn: () => api.leave.balances(), enabled: hasLeave });
  const mine = useQuery({ queryKey: ['leave', 'mine'], queryFn: () => api.leave.mine(), enabled: hasLeave });

  const firstName = user?.name?.split(' ')[0] ?? '';
  const waiting = (inbox.data?.items ?? []).filter((i) => i.actingAs !== 'OVERRIDE');
  const oldest = waiting[0];
  const annual = balances.data?.find((b) => b.trackBalance);
  const myPending = (mine.data ?? []).filter((r) => r.status === 'PENDING');
  const others = (people.data?.employees ?? []).filter((e) => e.id !== user?.employeeId);
  const licensable = ['attendance', 'leave', 'claims', 'reports'];

  const subtitle = approver && waiting.length > 0
    ? t('dashboard.needOk', { count: waiting.length })
    : myPending.length > 0
      ? t('dashboard.yoursWaiting', { count: myPending.length })
      : t('dashboard.signedInTo', { company: user?.companyName });

  return (
    <>
      <PageHeader
        eyebrow={formatDate(now, { weekday: 'long', day: 'numeric', month: 'long' })}
        docTitle={t('title.dashboard')}
        title={firstName ? t('dashboard.greetName', { greeting: t(greetingKey(now)), name: firstName }) : t(greetingKey(now))}
        subtitle={subtitle}
      />

      <div className="grid grid--stats">
        {approver && (
          <StatCard icon="shield" label={t('dashboard.waitingForYou')} value={inbox.data ? waiting.length : '–'}
            note={oldest ? t('dashboard.oldest', { when: relative(oldest.submittedAt) }) : t('dashboard.nothingToApprove')} />
        )}
        {seesTeamLeave && (
          <StatCard icon="calendar" label={t('dashboard.offToday')} value={off.data ? off.data.requests.length : '–'}
            note={off.data ? t('dashboard.approvedLeaveIn', { scope: SCOPE_LABEL[off.data.scope] }) : t('common.loading')} />
        )}
        {annual && (
          <StatCard icon="calendar" label={t('dashboard.typeLeft', { type: annual.name })} value={fmt(annual.available)}
            suffix={t('dashboard.ofTotal', { total: fmt(Number(annual.entitled) + Number(annual.carried)) })}
            note={Number(annual.pending) > 0 ? t('dashboard.pendingDays', { days: fmt(annual.pending) }) : t('dashboard.takenDays', { days: fmt(annual.used) })} />
        )}
        {canSeePeople && (
          <StatCard icon="users" label={t('dashboard.peopleYouSee')} value={people.data ? people.data.employees.length : '–'}
            note={people.data ? t('dashboard.access', { scope: SCOPE_LABEL[people.data.scope] }) : t('common.loading')} />
        )}
      </div>

      <div className="grid grid--main">
        <div className="col">
          {approver && (
            <Card title={t('dashboard.needsApproval')} count={waiting.length} action={<CardLink to="/app/approvals">{t('dashboard.seeAll')}</CardLink>}>
              <List>
                {waiting.slice(0, 4).map((i) => {
                  const meta = moduleMeta(i.module);
                  return (
                    <Row key={i.instanceId} lead={<Glyph icon={meta.icon} tone={toTone(i.tone, meta.tone)} />}
                      title={i.title} meta={`${i.requester} · ${relative(i.submittedAt)} · ${i.stepLabel}`}
                      to="/app/approvals"
                      trail={<Status tone={i.overdue ? 'red' : 'amber'}>{i.overdue ? t('dashboard.overdue') : t('status.PENDING')}</Status>} />
                  );
                })}
                {inbox.data && waiting.length === 0 && <Row title={t('dashboard.caughtUp')} meta={t('dashboard.caughtUpMeta')} />}
              </List>
            </Card>
          )}

          {hasLeave && (
            <Card title={t('dashboard.yourLeave')} count={mine.data?.filter((r) => r.endDate >= today && r.status !== 'CANCELLED' && r.status !== 'REJECTED').length}
              action={<CardLink to="/app/leave">{t('dashboard.seeAll')}</CardLink>}>
              <List>
                {(mine.data ?? []).filter((r) => r.endDate >= today && (r.status === 'PENDING' || r.status === 'APPROVED')).slice(0, 4).map((r) => (
                  <Row key={r.id} lead={<Glyph icon="calendar" tone={toTone(r.tone)} />}
                    title={`${r.typeName}, ${formatRange(r.startDate, r.endDate, r.dayPart)}`}
                    meta={r.pendingWith ? t('dashboard.with', { name: r.pendingWith.approver }) : t('status.APPROVED')} to="/app/leave"
                    trail={<Status tone={r.status === 'APPROVED' ? 'green' : 'amber'}>{r.status === 'APPROVED' ? t('status.APPROVED') : t('status.PENDING')}</Status>} />
                ))}
                {mine.data && !mine.data.some((r) => r.endDate >= today && (r.status === 'PENDING' || r.status === 'APPROVED')) && (
                  <Row title={t('dashboard.noLeave')} meta={t('dashboard.noLeaveMeta')} to="/app/leave" />
                )}
              </List>
            </Card>
          )}

          {canSeePeople && (
            <Card title={people.data?.scope === 'TENANT' ? t('nav.people') : t('dashboard.yourTeam')} count={others.length} action={<CardLink to="/app/people">{t('dashboard.seeAll')}</CardLink>}>
              <List>
                {others.slice(0, 5).map((e) => (
                  <Row key={e.id} lead={<Avatar name={e.fullName} />} title={e.fullName}
                    meta={[e.employeeNo, e.department, e.manager && t('dashboard.reportsTo', { name: e.manager })].filter(Boolean).join(' · ')}
                    to={`/app/people?q=${encodeURIComponent(e.fullName)}`} />
                ))}
                {people.data && others.length === 0 && <Row title={t('dashboard.noOne')} meta={t('dashboard.noOneMeta')} />}
              </List>
            </Card>
          )}
        </div>

        <div className="col">
          {seesTeamLeave && (
            <Card title={t('dashboard.offToday')} count={off.data?.requests.length} action={<CardLink to="/app/leave?tab=calendar">{t('dashboard.calendar')}</CardLink>}>
              <List>
                {off.data?.requests.map((r) => (
                  <Row key={r.id} lead={<Avatar name={r.employeeName} />} title={r.employeeName}
                    meta={`${r.typeName} · ${formatRange(r.startDate, r.endDate, r.dayPart)}`} />
                ))}
                {off.data && off.data.requests.length === 0 && <Row title={t('dashboard.everyoneIn')} />}
              </List>
            </Card>
          )}

          <Card title={t('shell.modules')} count={licensable.filter((m) => user?.modules.includes(m)).length}>
            <div className="tiles">
              {licensable.map((m) => {
                const on = !!user?.modules.includes(m);
                return (
                  <Tile key={m} icon={MODULE_META[m].icon} tone={MODULE_META[m].tone} name={MODULE_META[m].label}
                    status={<Status tone={on ? 'blue' : 'grey'}>{on ? t('common.on') : t('common.off')}</Status>} />
                );
              })}
            </div>
          </Card>
        </div>
      </div>
    </>
  );
}

function fmt(n: number | null) {
  const v = Number(n ?? 0);
  return Number.isInteger(v) ? String(v) : v.toFixed(1);
}
