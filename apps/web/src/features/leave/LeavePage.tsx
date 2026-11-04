import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import i18n from '../../i18n';
import type { DayPart, FileMeta, LeaveBalance, LeaveRequest, LeaveType } from '@hrms/api-client';
import { api } from '../../lib/api';
import { can, errorText, SCOPE_LABEL, useAccess } from '../../lib/access';
import { addDays, formatDate, formatDays, formatRange, isoDate, relative, toTone } from '../../lib/dates';
import { LEAVE_STATUS as STATUS } from '../../lib/leaveStatus';
import { Glyph } from '../../components/Glyph';
import { Icon } from '../../components/Icon';
import { Avatar, Card, CardLink, EmptyState, List, PageHeader, Row, StatCard, Status, Tabs } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { AttachmentButton, FileField } from '../../components/app/files';
import { Timeline } from '../approvals/Timeline';
import { LeaveCalendarTab } from './LeaveCalendarTab';
import { LeavePlannerTab } from './LeavePlannerTab';
import './leave.css';

const MONTH = (iso: string) => formatDate(iso, { day: 'numeric', month: 'long' });

const TABS = ['overview', 'calendar', 'planner'] as const;
type Tab = (typeof TABS)[number];

type Editor = { mode: 'apply' } | { mode: 'edit'; request: LeaveRequest } | { mode: 'record' };

/** Your leave: balances, requests and applying; plus who's off, for people your leave:view scope covers. */
export function LeavePage() {
  const { t } = useTranslation();
  const { user } = useAccess();
  const [today] = useState(() => isoDate(new Date()));
  const [params, setParams] = useSearchParams();
  const [editor, setEditorState] = useState<Editor | null>(() => (params.get('apply') === '1' ? { mode: 'apply' } : null));
  const [picked, setPicked] = useState<string | null>(null);
  // Opened from a notification: /app/leave?request=<id>
  const viewing = params.get('request') ?? picked;
  const setViewing = (id: string | null) => {
    setPicked(id);
    if (params.get('request')) setParams({}, { replace: true });
  };
  const canApply = can(user, 'leave:apply') && !!user?.employeeId;
  const canManage = can(user, 'leave:manage');
  const seesOthers = can(user, 'leave:view', 'TEAM');
  const setEditor = (e: Editor | null) => {
    setEditorState(e);
    if (!e && params.get('apply')) setParams({}, { replace: true });
  };
  const requested = params.get('tab') as Tab | null;
  const tab: Tab = requested && TABS.includes(requested) && (requested !== 'planner' || canApply) ? requested : 'overview';
  const setTab = (key: string) => setParams(key === 'overview' ? {} : { tab: key }, { replace: true });
  const [planFrom, setPlanFrom] = useState<string | null>(null);
  const drafts = useQuery({ queryKey: ['leave', 'drafts'], queryFn: api.leave.drafts.list, enabled: canApply });

  const balances = useQuery({ queryKey: ['leave', 'balances', 'me'], queryFn: () => api.leave.balances(), enabled: !!user?.employeeId });
  const mine = useQuery({ queryKey: ['leave', 'mine'], queryFn: () => api.leave.mine(), enabled: !!user?.employeeId });
  const holidays = useQuery({ queryKey: ['leave', 'holidays'], queryFn: () => api.leave.holidays() });
  const team = useQuery({
    queryKey: ['leave', 'calendar', today],
    queryFn: () => api.leave.calendar(today, addDays(today, 30), true),
    enabled: seesOthers,
  });
  const upcomingHolidays = (holidays.data ?? []).filter((h) => h.date >= today).slice(0, 5);
  const offToday = (team.data?.requests ?? []).filter((r) => r.status === 'APPROVED' && r.startDate <= today && r.endDate >= today);
  const returned = (mine.data ?? []).filter((r) => r.status === 'RETURNED');

  return (
    <>
      <PageHeader
        title={t('nav.leave')}
        subtitle={t('leave.subtitle', { year: today.slice(0, 4) })}
        actions={
          <>
            {canManage && <button type="button" className="btn" onClick={() => setEditor({ mode: 'record' })}>{t('leave.recordFor')}</button>}
            {canApply && <button type="button" className="btn primary" onClick={() => setEditor({ mode: 'apply' })}><Icon name="plus" size={16} />{t('leave.apply')}</button>}
          </>
        }
      />
      {(balances.isError || mine.isError) && <p className="form-error" role="alert">{errorText(balances.error ?? mine.error)}</p>}

      {returned.map((r) => (
        <div key={r.id} className="notice notice--action" role="status">
          <span>
            <strong>{r.typeName}, {formatRange(r.startDate, r.endDate, r.dayPart)}</strong> {t('leave.sentBack', { comment: r.returnedComment })}
          </span>
          <span className="panel-actions">
            <button type="button" className="btn small primary" onClick={() => setEditor({ mode: 'edit', request: r })}>{t('leave.editResubmit')}</button>
            <button type="button" className="btn small" onClick={() => setViewing(r.id)}>{t('common.view')}</button>
          </span>
        </div>
      ))}

      <Tabs titled
        label={t('nav.leave')}
        value={tab}
        onChange={setTab}
        tabs={[
          { key: 'overview', label: t('leave.tabs.overview') },
          { key: 'calendar', label: t('leave.tabs.calendar'), icon: 'calendar' },
          ...(canApply ? [{ key: 'planner', label: t('leave.tabs.planner'), count: drafts.data?.length || undefined }] : []),
        ]}
      >
      {tab === 'calendar' && (
        <LeaveCalendarTab
          seesOthers={seesOthers}
          hasEmployee={!!user?.employeeId}
          onOpen={setViewing}
          onPlan={(iso) => { setPlanFrom(iso); setTab('planner'); }}
        />
      )}
      {tab === 'planner' && <LeavePlannerTab key={planFrom ?? 'plan'} balances={balances.data ?? []} startOn={planFrom} />}
      {tab === 'overview' && (<>
      <div className="grid grid--stats">
        {balances.data?.map((b) => (
          <StatCard
            key={b.leaveTypeId}
            icon="calendar"
            label={b.name}
            value={b.trackBalance ? fmt(b.available) : fmt(b.used)}
            suffix={b.trackBalance ? t('leave.ofLeft', { total: fmt(Number(b.entitled) + Number(b.carried)) }) : t('leave.daysTaken')}
            note={balanceNote(b, today)}
          />
        ))}
      </div>

      <div className="grid grid--main">
        <Card title={t('leave.yourRequests')} count={mine.data?.length}>
          {mine.data && mine.data.length === 0 ? (
            <EmptyState icon="calendar" tone="teal" title={t('leave.noneYet')} action={canApply && <button type="button" className="btn" onClick={() => setEditor({ mode: 'apply' })}>{t('leave.apply')}</button>}>
              {t('leave.noneYetBody')}
            </EmptyState>
          ) : (
            <List>
              {mine.data?.map((r) => (
                <Row
                  key={r.id}
                  lead={<Glyph icon="calendar" tone={toTone(r.tone)} />}
                  title={`${r.typeName}, ${formatRange(r.startDate, r.endDate, r.dayPart)}`}
                  meta={[
                    formatDays(r.days),
                    r.pendingWith ? t('leave.withApprover', { name: r.pendingWith.approver, step: r.pendingWith.label })
                      : r.status === 'RETURNED' ? t('leave.changeResubmit')
                        : r.decidedAt ? t('leave.decided', { when: relative(r.decidedAt) }) : null,
                    r.recordedBy && t('leave.recordedBy', { name: r.recordedBy }),
                    r.attachment && t('leave.docAttached'),
                    r.reason,
                  ].filter(Boolean).join(' · ')}
                  trail={
                    <>
                      <Status tone={STATUS[r.status].tone}>{STATUS[r.status].label}</Status>
                      <button type="button" className="btn small" onClick={() => setViewing(r.id)}>{t('common.view')}</button>
                    </>
                  }
                />
              ))}
            </List>
          )}
        </Card>

        <div className="col">
          {seesOthers && (
            <Card title={t('leave.whosOff')} count={offToday.length} action={team.data && <Status tone="blue">{SCOPE_LABEL[team.data.scope]}</Status>}>
              <List>
                {(team.data?.requests ?? []).slice(0, 8).map((r) => (
                  <Row
                    key={r.id}
                    lead={<Avatar name={r.employeeName} />}
                    title={r.employeeName}
                    meta={`${r.typeName} · ${formatRange(r.startDate, r.endDate, r.dayPart)}`}
                    onClick={() => setViewing(r.id)}
                    trail={r.startDate <= today && r.endDate >= today && r.status === 'APPROVED'
                      ? <Status tone="blue">{t('leave.offToday')}</Status>
                      : <Status tone={STATUS[r.status].tone}>{r.status === 'PENDING' ? t('status.PENDING') : t('leave.upcoming')}</Status>}
                  />
                ))}
                {team.data && team.data.requests.length === 0 && <Row title={t('leave.nobodyOff')} />}
              </List>
            </Card>
          )}

          <Card title={t('leave.holidays')} action={canManage ? <CardLink to="/app/leave/settings?tab=holidays">{t('common.manage')}</CardLink> : undefined}>
            <List>
              {upcomingHolidays.map((h) => (
                <Row key={h.id} lead={<Glyph icon="star" tone="purple" />} title={h.name}
                  meta={formatDate(h.date, { weekday: 'long', day: 'numeric', month: 'long' })} />
              ))}
              {holidays.data && upcomingHolidays.length === 0 && <Row title={t('leave.noMoreHolidays')} />}
            </List>
          </Card>
        </div>
      </div>
      </>)}
      </Tabs>

      {editor && <ApplyPanel editor={editor} myBalances={balances.data ?? []} onClose={() => setEditor(null)} />}
      {viewing && (
        <RequestPanel
          id={viewing}
          meId={user?.employeeId ?? null}
          canManage={canManage}
          onEdit={(r) => { setViewing(null); setEditor({ mode: 'edit', request: r }); }}
          onClose={() => setViewing(null)}
        />
      )}
    </>
  );
}

function fmt(n: number | null) {
  const v = Number(n ?? 0);
  return Number.isInteger(v) ? String(v) : v.toFixed(1);
}

function balanceNote(b: LeaveBalance, today: string) {
  const t = i18n.t;
  if (!b.trackBalance) return t('leave.noLimit');
  const parts = [t('leave.nTaken', { n: fmt(b.used) })];
  if (Number(b.pending) > 0) parts.push(t('leave.nWaiting', { n: fmt(b.pending) }));
  if (Number(b.planned) > 0) parts.push(t('leave.nPlanned', { n: fmt(b.planned) }));
  if (Number(b.carriedExpired) > 0) parts.push(t('leave.carryExpired', { n: fmt(b.carriedExpired), date: MONTH(b.carriedExpiresOn!) }));
  else if (Number(b.carried) > 0 && b.carriedExpiresOn && b.carriedExpiresOn >= today) parts.push(t('leave.carryUseBy', { n: fmt(b.carried), date: MONTH(b.carriedExpiresOn) }));
  else if (Number(b.carried) > 0) parts.push(t('leave.carryIncludes', { n: fmt(b.carried) }));
  parts.push(b.basis);
  return parts.join(' · ');
}

/** Apply, edit a returned request, or (HR) record leave for someone. */
function ApplyPanel({ editor, myBalances, onClose }: { editor: Editor; myBalances: LeaveBalance[]; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const editing = editor.mode === 'edit' ? editor.request : null;
  const recording = editor.mode === 'record';
  const types = useQuery({ queryKey: ['leave', 'types'], queryFn: api.leave.types });
  const people = useQuery({ queryKey: ['employees', ''], queryFn: () => api.employees(), enabled: recording });
  const [tomorrow] = useState(() => addDays(isoDate(new Date()), 1));
  const [employeeId, setEmployeeId] = useState('');
  const [typeId, setTypeId] = useState(editing?.leaveTypeId ?? '');
  const [start, setStart] = useState(editing?.startDate ?? tomorrow);
  const [end, setEnd] = useState(editing?.endDate ?? tomorrow);
  const [part, setPart] = useState<DayPart>(editing?.dayPart ?? 'FULL');
  const [reason, setReason] = useState(editing?.reason ?? '');
  const [file, setFile] = useState<FileMeta | null>(editing?.attachment ?? null);
  const [approveNow, setApproveNow] = useState(true);
  const year = Number(start.slice(0, 4));

  const theirBalances = useQuery({
    queryKey: ['leave', 'entitlements', employeeId, year],
    queryFn: () => api.leave.settings.entitlements(employeeId, year),
    enabled: recording && !!employeeId,
  });
  const balances = recording ? theirBalances.data ?? [] : myBalances;

  const type: LeaveType | undefined = types.data?.find((t) => t.id === (typeId || types.data?.[0]?.id));
  const oneDay = start === end;
  const effectivePart: DayPart = oneDay && type?.halfDayAllowed ? part : 'FULL';
  const valid = !!type && !!start && !!end && end >= start && (!recording || !!employeeId);
  // Preview uses the signed-in person's working week; for HR recording it's indicative only.
  const preview = useQuery({
    queryKey: ['leave', 'preview', start, end, effectivePart],
    queryFn: () => api.leave.preview(start, end, effectivePart),
    enabled: !!type && end >= start,
  });
  const balance = balances.find((b) => b.leaveTypeId === type?.id);
  const days = preview.data ? Number(preview.data.days) : null;
  const short = balance?.trackBalance && days !== null && Number(balance.available) < days;
  const needsFile = !!type?.requiresAttachment;

  const submit = useMutation({
    mutationFn: () => {
      const input = { leaveTypeId: type!.id, startDate: start, endDate: end, dayPart: effectivePart, reason: reason || undefined, attachmentId: file?.id ?? null };
      if (editing) return api.leave.resubmit(editing.id, input);
      if (recording) return api.leave.recordFor({ ...input, employeeId, approveNow });
      return api.leave.apply(input);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['leave'] });
      await queryClient.invalidateQueries({ queryKey: ['approvals'] });
      onClose();
    },
  });

  const title = editing ? t('leave.form.editTitle') : recording ? t('leave.recordFor') : t('leave.apply');
  const action = editing ? t('leave.form.resubmit') : recording && approveNow ? t('leave.form.recordApproved') : t('leave.form.send');

  return (
    <Panel title={title} onClose={onClose}>
      <form
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          submit.mutate();
        }}
      >
        {editing?.returnedComment && <p className="notice">{t('leave.form.sentBackNote', { comment: editing.returnedComment })}</p>}
        {submit.error && <p className="form-error" role="alert">{errorText(submit.error)}</p>}
        {recording && (
          <label className="field">
            {t('req.employee')}
            <select value={employeeId} onChange={(e) => setEmployeeId(e.target.value)} required>
              <option value="">{t('leave.form.choosePerson')}</option>
              {people.data?.employees.map((e) => <option key={e.id} value={e.id}>{e.fullName} ({e.employeeNo})</option>)}
            </select>
          </label>
        )}
        <label className="field">
          {t('leave.form.type')}
          <select value={type?.id ?? ''} onChange={(e) => setTypeId(e.target.value)} required>
            {types.data?.map((lt) => {
              const b = balances.find((x) => x.leaveTypeId === lt.id);
              return <option key={lt.id} value={lt.id}>{lt.name}{b?.trackBalance ? ` ${t('leave.form.nLeft', { n: fmt(b.available) })}` : ''}</option>;
            })}
          </select>
        </label>
        <div className="field-row">
          <label className="field">{t('leave.form.from')}<input type="date" value={start} onChange={(e) => { setStart(e.target.value); if (e.target.value > end) setEnd(e.target.value); }} required /></label>
          <label className="field">{t('leave.form.to')}<input type="date" value={end} min={start} onChange={(e) => setEnd(e.target.value)} required /></label>
        </div>
        {oneDay && type?.halfDayAllowed && (
          <fieldset>
            <legend>{t('leave.form.howMuch')}</legend>
            <div className="segmented" role="radiogroup">
              {(['FULL', 'AM', 'PM'] as DayPart[]).map((p) => (
                <label key={p} className={part === p ? 'is-on' : undefined}>
                  <input type="radio" name="part" value={p} checked={part === p} onChange={() => setPart(p)} />
                  {p === 'FULL' ? t('dates.fullDay') : p === 'AM' ? t('dates.morningCap') : t('dates.afternoonCap')}
                </label>
              ))}
            </div>
          </fieldset>
        )}
        <label className="field">
          {t('req.reason')} <span className="hint">{t('leave.form.reasonHint')}</span>
          <textarea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} />
        </label>
        <FileField
          label={needsFile ? t('leave.form.docRequired') : t('leave.form.doc')}
          required={needsFile}
          current={file}
          onChange={setFile}
        />

        <div className={short ? 'apply-summary is-short' : 'apply-summary'} aria-live="polite">
          {days === null ? (
            <span>{t('leave.form.chooseDates')}</span>
          ) : days === 0 ? (
            <span>{t('leave.form.noWorkingDays')}</span>
          ) : (
            <>
              <strong>{formatDays(days)}</strong>
              <span>
                {!balance ? '' : ` · ${balance.trackBalance
                  ? short
                    ? t(recording ? 'leave.form.shortThey' : 'leave.form.shortYou', { left: fmt(balance.available), over: fmt(days - Number(balance.available)) })
                    : t('leave.form.leftAfter', { n: fmt(Number(balance.available) - days) })
                  : t('leave.form.noLimit')}`}
              </span>
              {preview.data && preview.data.holidays.length > 0 && (
                <span className="hint">{t('leave.form.notCounted', { names: preview.data.holidays.map((h) => h.name).join(', ') })}</span>
              )}
              {recording && <span className="hint">{t('leave.form.theirWeek')}</span>}
            </>
          )}
        </div>

        {recording ? (
          <label className="check-line">
            <input type="checkbox" checked={approveNow} onChange={(e) => setApproveNow(e.target.checked)} />
            {t('leave.form.approvedAlready')}
          </label>
        ) : type && (
          <p className="hint">
            {t(type.approvalLevels === 0 ? 'leave.form.goesHr' : type.approvalLevels === 1
              ? (type.hrApproval ? 'leave.form.goesManagerHr' : 'leave.form.goesManager')
              : (type.hrApproval ? 'leave.form.goesTwoHr' : 'leave.form.goesTwo'))}
          </p>
        )}
        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={!valid || submit.isPending || days === 0 || !!short || (needsFile && !file)}>
            {submit.isPending ? t('leave.form.sending') : action}
          </button>
          {needsFile && !file && <span className="hint">{t('leave.form.attachToContinue')}</span>}
        </div>
      </form>
    </Panel>
  );
}

function RequestPanel({ id, meId, canManage, onEdit, onClose }: {
  id: string; meId: string | null;
  canManage: boolean; onEdit: (r: LeaveRequest) => void; onClose: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const detail = useQuery({ queryKey: ['leave', 'request', id], queryFn: () => api.leave.detail(id) });
  const [hrReason, setHrReason] = useState('');
  const done = async () => {
    await queryClient.invalidateQueries({ queryKey: ['leave'] });
    onClose();
  };
  const cancel = useMutation({ mutationFn: () => api.leave.cancel(id), onSuccess: done });
  const hrCancel = useMutation({ mutationFn: () => api.leave.cancelByHr(id, hrReason), onSuccess: done });
  const r = detail.data?.request;
  const mine = !!r && r.employeeId === meId;
  const error = detail.error ?? cancel.error ?? hrCancel.error;

  return (
    <Panel title={r ? `${r.typeName}, ${formatRange(r.startDate, r.endDate, r.dayPart)}` : t('leave.request')} onClose={onClose}>
      {error && <p className="form-error" role="alert">{errorText(error)}</p>}
      {r && (
        <>
          {r.status === 'RETURNED' && r.returnedComment && <p className="notice">{t('leave.sentBackNotice', { comment: r.returnedComment })}</p>}
          {r.status === 'CANCELLED' && r.cancelReason && <p className="notice">{t('leave.cancelledByHr', { reason: r.cancelReason })}</p>}
          <dl className="facts">
            {!mine && <><dt>{t('req.employee')}</dt><dd>{r.employeeName}</dd></>}
            <dt>{t('req.status')}</dt><dd><Status tone={STATUS[r.status].tone}>{STATUS[r.status].label}</Status></dd>
            <dt>{t('req.workingDays')}</dt><dd>{formatDays(r.days)}</dd>
            <dt>{t('req.reason')}</dt><dd>{r.reason ?? '—'}</dd>
            <dt>{t('req.document')}</dt><dd>{r.attachment ? <AttachmentButton requestId={r.id} file={r.attachment} /> : '—'}</dd>
            {r.recordedBy && <><dt>{t('req.recordedBy')}</dt><dd>{r.recordedBy}</dd></>}
            <dt>{r.status === 'DRAFT' ? t('leave.planned') : t('req.submitted')}</dt><dd>{relative(r.createdAt)}</dd>
          </dl>
          {detail.data!.timeline.length > 0 ? (
            <section className="stack">
              <h3 className="panel-subhead">{t('req.chain')}</h3>
              <Timeline steps={detail.data!.timeline} />
            </section>
          ) : r.recordedBy && r.status !== 'PENDING' && <p className="hint">{t('leave.recordedApproved')}</p>}

          {mine && (r.canEdit || r.canCancel) && (
            <div className="panel-actions">
              {r.canEdit && <button type="button" className="btn primary" onClick={() => onEdit(r)}>{t('leave.editResubmit')}</button>}
              {r.canCancel && (
                <button type="button" className="btn reject" onClick={() => cancel.mutate()} disabled={cancel.isPending}>
                  {r.status === 'APPROVED' ? t('leave.cancelLeave') : t('leave.withdraw')}
                </button>
              )}
            </div>
          )}

          {canManage && (r.status === 'APPROVED' || r.status === 'RETURNED') && (
            <form
              className="stack hr-box"
              onSubmit={(e) => {
                e.preventDefault();
                hrCancel.mutate();
              }}
            >
              <h3 className="panel-subhead">{t('leave.hrCancel')}</h3>
              <p className="hint">{t('leave.hrCancelHint')}</p>
              <label className="field">
                {t('req.reason')}
                <input value={hrReason} onChange={(e) => setHrReason(e.target.value)} required maxLength={300} placeholder={t('leave.hrCancelExample')} />
              </label>
              <div className="panel-actions">
                <button type="submit" className="btn reject" disabled={hrCancel.isPending || !hrReason.trim()}>{t('leave.cancelLeave')}</button>
              </div>
            </form>
          )}
        </>
      )}
    </Panel>
  );
}
