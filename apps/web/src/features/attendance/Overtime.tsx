import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import {
  type OvertimeCompensation, type OvertimeDayKind, type OvertimeDetail, type OvertimeRequest, type OvertimeRule, type OvertimeTimeOff,
  type WorkSession,
} from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { addDays, formatDate, isoDate, relative } from '../../lib/dates';
import { useUrlFilters } from '../../lib/filters';
import { Card, Status } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Table } from '../../components/app/Table';
import { Icon } from '../../components/Icon';
import { Timeline } from '../approvals/Timeline';
import { CorrectionStatusChip } from './Corrections';
import { num, rateForDay, rateText, ruleRates, ruleText } from './overtimeText';
import './attendance.css';

/** How far back overtime can be asked for (the server's limit). */
const MAX_AGE_DAYS = 60;
/** How far ahead overtime can be asked for (planned, approved before it's worked). */
const MAX_AHEAD_DAYS = 30;
const MAX_REASON = 500;

const hhmm = (time: string | null | undefined) => (time ? time.slice(0, 5) : '');
const dayLabel = (date: string) => formatDate(date, { weekday: 'short', day: 'numeric', month: 'short' });

/** "2.5 h at 1.5×", "4 h as time off", "4 h as time off at 2.0×" */
function overtimeText(t: TFunction, o: { hours: number; rate: number | null; compensation: OvertimeCompensation }) {
  const base = o.compensation === 'TIME_OFF' ? t('overtime.hoursTimeOff', { hours: num(o.hours) }) : t('overtime.hoursOnly', { hours: num(o.hours) });
  return o.rate !== null ? t('overtime.atRate', { text: base, rate: rateText(o.rate) }) : base;
}

/** "½ day", "1 day", "1½ days" in the person's language. */
function daysText(t: TFunction, days: number) {
  return t('overtime.days', { count: Number(days), n: num(days) });
}

function worked(t: TFunction, minutes: number) {
  return minutes > 0 ? t('timesheet.hm', { h: Math.floor(minutes / 60), m: String(minutes % 60).padStart(2, '0') }) : t('overtime.noTime');
}

function sessionsText(sessions: WorkSession[], none: string) {
  return sessions.length ? sessions.map((s) => `${hhmm(s.in)}–${s.out ? hhmm(s.out) : '?'}`).join(', ') : none;
}

/** Quarter hours from 0.25 to 24. */
const validHours = (h: number) => Number.isFinite(h) && h > 0 && h <= 24 && Number.isInteger(h * 4);


/**
 * Attendance → Overtime: how your overtime is compensated, your banked hours, your requests (newest day first), and a
 * button to ask for more. The open request is in the URL as ?overtime=, so notifications can link to it.
 */
export function MyOvertime() {
  const { t } = useTranslation();
  const filters = useUrlFilters([]);
  const openId = filters.param('overtime');
  const setOpen = (id: string | null) => filters.setParam('overtime', id);
  // Opened from a timesheet day with time beyond the shift: ?ask=<date>&hours=<h>.
  const asked = filters.param('ask');
  const [form, setForm] = useState<{ editing: OvertimeRequest | null; date?: string; hours?: string } | null>(
    asked ? { editing: null, date: asked, hours: filters.param('hours') ?? undefined } : null);
  const list = useQuery({ queryKey: ['attendance', 'overtime', 'mine'], queryFn: api.attendance.overtime.mine });
  const mine = useQuery({ queryKey: ['attendance', 'overtime', 'rule'], queryFn: api.attendance.overtime.myRule });
  const rows = list.data ?? [];
  const approved = rows.filter((o) => o.status === 'APPROVED');
  const paidHours = approved.filter((o) => o.compensation === 'PAY').reduce((n, o) => n + Number(o.hours), 0);
  const earnedDays = approved.reduce((n, o) => n + Number(o.creditedDays ?? 0), 0);
  const waiting = rows.filter((o) => o.status === 'PENDING').length;
  const rule = mine.data?.rule;
  const timeOff = !!rule && rule.compensation !== 'PAY' && !!rule.leaveTypeId;

  return (
    <div className="att-stack">
      {mine.isError && <p className="form-error" role="alert">{errorText(mine.error)}</p>}
      {rule && (
        <p className="notice ot-rule" role="note">
          <strong>{t('overtime.yourRule')}</strong> {ruleText(t, rule)}
          {timeOff && rule.conversion === 'BANKED' && (
            <> · {t('overtime.banked', { hours: num(mine.data!.bankHours), need: num(Math.max(0, Number(rule.hoursPerUnit) - Number(mine.data!.bankHours))) })}</>
          )}
        </p>
      )}
      <Card
        title={t('overtime.mine')}
        count={list.data?.length}
        action={<button type="button" className="btn primary small" disabled={!rule} onClick={() => setForm({ editing: null })}><Icon name="plus" size={14} />{t('overtime.ask')}</button>}
      >
        {list.isError && <p className="form-error card-pad" role="alert">{errorText(list.error)}</p>}
        {rows.length > 0 && (
          <p className="muted card-pad">
            {[t('overtime.paidHours', { hours: num(paidHours) }), earnedDays > 0 && t('overtime.earned', { days: daysText(t, earnedDays) }),
              t('overtime.waiting', { count: waiting })].filter(Boolean).join(' · ')}
          </p>
        )}
        <Table
          label={t('overtime.mine')}
          rows={rows}
          rowKey={(o) => o.id}
          loading={list.isPending}
          empty={t('overtime.empty')}
          pageSize={10}
          pageSizes={[]}
          columns={[
            { key: 'date', header: t('overtime.date'), rowHeader: true, cell: (o) => (
              <button type="button" className="link-button" onClick={() => setOpen(o.id)}>{dayLabel(o.date)}</button>
            ) },
            { key: 'hours', header: t('overtime.hours'), align: 'end', cell: (o) => num(o.hours) },
            { key: 'as', header: t('overtime.as'), cell: (o) => (
              o.compensation === 'TIME_OFF'
                ? <>{t('overtime.timeOff')}{o.creditedDays !== null && <span className="muted"> · {Number(o.creditedDays) > 0 ? daysText(t, o.creditedDays) : t('overtime.inBank')}</span>}</>
                : <>{t('overtime.pay')}{o.rate !== null && <span className="muted"> · {rateText(o.rate)}</span>}</>
            ) },
            { key: 'reason', header: t('overtime.reason'), cell: (o) => <span className="muted">{o.reason}</span> },
            { key: 'status', header: t('overtime.status'), cell: (o) => (
              <span className="corr-status">
                <CorrectionStatusChip status={o.status} />
                {o.planned && <Status tone="blue">{t('overtime.planned')}</Status>}
                {o.pendingWith && <span className="muted">{t('correction.waitingFor', { name: o.pendingWith.approver })}</span>}
              </span>
            ) },
          ]}
        />
      </Card>
      {openId && <OvertimePanel id={openId} onEdit={(o) => { setOpen(null); setForm({ editing: o }); }} onClose={() => setOpen(null)} />}
      {form && rule && <OvertimeForm editing={form.editing} rule={rule} bankHours={mine.data!.bankHours} initialDate={form.date}
        initialHours={form.hours} onClose={() => { setForm(null); if (asked) filters.setParams({ ask: null, hours: null }); }} />}
    </div>
  );
}

/** What the hours would earn under the rule, worked out like the server does (an estimate until approved). */
function estimate(rule: OvertimeRule, hours: number, rate: number | null, bank: number): { days: number; bankAfter: number; lost: number } {
  const counted = rule.applyRate && rate !== null ? hours * rate : hours;
  const per = Number(rule.hoursPerUnit);
  const unit = rule.creditUnit === 'FULL_DAY' ? 1 : 0.5;
  const pool = rule.conversion === 'BANKED' ? Number(bank) + counted : counted;
  const units = Math.floor(pool / per + 1e-9);
  const rest = Math.round((pool - units * per) * 100) / 100;
  return rule.conversion === 'BANKED' ? { days: units * unit, bankAfter: rest, lost: 0 } : { days: units * unit, bankAfter: Number(bank), lost: rest };
}

/** Ask for overtime on a day, or change a request that was sent back. */
function OvertimeForm({ editing, rule, bankHours, initialDate, initialHours, onClose }: {
  editing: OvertimeRequest | null; rule: OvertimeRule; bankHours: number; initialDate?: string; initialHours?: string; onClose: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [today] = useState(() => isoDate(new Date()));
  const [date, setDate] = useState(editing?.date ?? initialDate ?? today);
  const [hours, setHours] = useState(editing ? String(Number(editing.hours)) : initialHours ?? '');
  // EMPLOYEE rates: what they picked (null: the rate for the day).
  const [picked, setPicked] = useState<number | null>(rule.rateMode === 'EMPLOYEE' && editing?.rate != null ? Number(editing.rate) : null);
  const [reason, setReason] = useState(editing?.reason ?? '');
  const timeOffAllowed = rule.compensation !== 'PAY' && !!rule.leaveTypeId;
  const [compensation, setCompensation] = useState<OvertimeCompensation>(
    editing?.compensation ?? (timeOffAllowed && rule.compensation === 'TIME_OFF' ? 'TIME_OFF' : 'PAY'));
  const chosen: OvertimeCompensation = !timeOffAllowed ? 'PAY' : rule.compensation === 'TIME_OFF' ? 'TIME_OFF' : compensation;
  const rateNeeded = chosen === 'PAY' || rule.applyRate;
  // With pre-approval, only today onwards; otherwise up to 60 days back. Either way up to 30 days ahead (planned).
  const oldest = rule.requirePreApproval ? today : addDays(today, -MAX_AGE_DAYS);
  const latest = addDays(today, MAX_AHEAD_DAYS);
  const dateOk = !!date && date <= latest && date >= oldest;
  const planned = dateOk && date > today;

  // The day's clock-ins, so you can see what you worked.
  const day = useQuery({
    queryKey: ['attendance', 'timesheet', 'me', date, date],
    queryFn: () => api.attendance.timesheet('me', { from: date, to: date }),
    enabled: dateOk && !planned,
  });
  const d = day.data?.days[0];
  const kind: OvertimeDayKind = d?.offReason === 'HOLIDAY' ? 'HOLIDAY' : d?.offReason === 'REST_DAY' ? 'REST' : 'WORKING';
  const dayRate = rateForDay(rule, kind);
  const rate = rule.rateMode === 'EMPLOYEE' && picked !== null ? picked : dayRate;

  const submit = useMutation({
    mutationFn: () => {
      const input = {
        date, hours: Number(hours), rate: rateNeeded ? rate : null, reason: reason.trim(),
        compensation: rule.compensation === 'CHOICE' && timeOffAllowed ? chosen : null,
      };
      return editing ? api.attendance.overtime.resubmit(editing.id, input) : api.attendance.overtime.submit(input);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['attendance'] });
      await queryClient.invalidateQueries({ queryKey: ['approvals'] });
      onClose();
    },
  });
  const h = Number(hours);
  const hoursBad = hours !== '' && !validHours(h);
  const valid = dateOk && validHours(h) && reason.trim().length > 0;
  const est = chosen === 'TIME_OFF' && validHours(h) ? estimate(rule, h, rateNeeded ? rate : null, bankHours) : null;

  return (
    <Panel title={editing ? t('overtime.editTitle', { date: dayLabel(editing.date) }) : t('overtime.title')} onClose={onClose}>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); submit.mutate(); }}>
        {editing?.returnedComment && <p className="notice">{t('correction.sentBack', { comment: editing.returnedComment })}</p>}
        {submit.error && <p className="form-error" role="alert">{errorText(submit.error)}</p>}
        <label className="field">
          {t('overtime.date')}
          <input type="date" value={date} min={oldest} max={latest} onChange={(e) => setDate(e.target.value)} required />
        </label>
        {planned && <p className="notice" role="status">{t('overtime.plannedHint')}</p>}
        {dateOk && !planned && (
          <dl className="facts">
            <dt>{t('overtime.clockIns')}</dt>
            <dd>{d ? `${sessionsText(d.sessions, t('correction.none'))} · ${worked(t, d.workedMin)}` : day.isPending ? '…' : t('correction.none')}</dd>
            {d && d.overtimeMin > 0 && <><dt>{t('overtime.alreadyApproved')}</dt><dd>{worked(t, d.overtimeMin)}</dd></>}
          </dl>
        )}
        <label className="field">
          {t('overtime.hours')}
          <input type="number" inputMode="decimal" min={0.25} max={24} step={0.25} value={hours}
            onChange={(e) => setHours(e.target.value)} required aria-invalid={hoursBad || undefined} />
        </label>
        <p className={hoursBad ? 'form-error' : 'hint'} role={hoursBad ? 'alert' : undefined}>{t('overtime.hoursHint')}</p>

        {timeOffAllowed && rule.compensation === 'CHOICE' && (
          <>
            <span className="wf-label" id="ot-comp-label">{t('overtime.as')}</span>
            <div className="segmented" role="radiogroup" aria-labelledby="ot-comp-label">
              {(['PAY', 'TIME_OFF'] as const).map((c) => (
                <label key={c} className={chosen === c ? 'is-on' : undefined}>
                  <input type="radio" name="ot-comp" checked={chosen === c} onChange={() => setCompensation(c)} />
                  {c === 'PAY' ? t('overtime.payMe') : t('overtime.giveTimeOff', { type: rule.leaveTypeName })}
                </label>
              ))}
            </div>
          </>
        )}
        {timeOffAllowed && rule.compensation === 'TIME_OFF' && <p className="hint">{t('overtime.alwaysTimeOff', { type: rule.leaveTypeName })}</p>}

        {rateNeeded && rule.rateMode === 'AUTO' && (
          <p className="notice" role="status">
            {t('overtime.autoRate', { kind: t(`overtime.dayKind.${kind}`), rate: rateText(dayRate) })}
            {chosen === 'TIME_OFF' && <> {t('overtime.rateTimeOffHint')}</>}
          </p>
        )}
        {rateNeeded && rule.rateMode === 'EMPLOYEE' && (
          <>
            <span className="wf-label" id="ot-rate-label">{t('overtime.rate')}</span>
            <div className="segmented" role="radiogroup" aria-labelledby="ot-rate-label">
              {ruleRates(rule).map((r) => (
                <label key={r} className={rate === r ? 'is-on' : undefined}>
                  <input type="radio" name="ot-rate" checked={rate === r} onChange={() => setPicked(r)} />
                  {rateText(r)}
                </label>
              ))}
            </div>
            <p className="hint">
              {t('overtime.suggestedRate', { kind: t(`overtime.dayKind.${kind}`), rate: rateText(dayRate) })}
              {chosen === 'TIME_OFF' && <> {t('overtime.rateTimeOffHint')}</>}
            </p>
          </>
        )}

        {est && (
          <p className="notice" role="status">
            {est.days > 0 ? t('overtime.wouldEarn', { days: daysText(t, est.days), type: rule.leaveTypeName }) : t('overtime.wouldEarnNothing')}
            {rule.conversion === 'BANKED' && <> {t('overtime.bankAfter', { hours: num(est.bankAfter) })}</>}
            {rule.conversion === 'PER_REQUEST' && est.lost > 0 && <> {t('overtime.lost', { hours: num(est.lost) })}</>}
            {' '}<span className="muted">{t('overtime.estimate')}</span>
          </p>
        )}

        <label className="field">
          {t('overtime.reason')}
          <textarea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={MAX_REASON} required placeholder={t('overtime.reasonHint')} />
        </label>
        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={!valid || submit.isPending}>
            {t(editing ? 'correction.resend' : 'correction.send')}
          </button>
        </div>
      </form>
    </Panel>
  );
}

function TimeOffText({ off }: { off: OvertimeTimeOff }) {
  const { t } = useTranslation();
  const earns = Number(off.days) > 0 ? daysText(t, off.days) : null;
  return (
    <>
      {t('overtime.countedHours', { hours: num(off.countedHours) })} →{' '}
      {earns ? t(off.done ? 'overtime.gave' : 'overtime.gives', { days: earns, type: off.leaveType }) : off.done ? t('overtime.wentToBank') : t('overtime.wouldEarnNothing')}
      {!off.done && off.bankHours !== null && off.conversion === 'BANKED' && <> · {t('overtime.bankAfter', { hours: num(off.bankHours) })}</>}
      {!off.done && off.lostHours !== null && Number(off.lostHours) > 0 && <> · {t('overtime.lost', { hours: num(off.lostHours) })}</>}
    </>
  );
}

/** The facts of one request, for its owner and for approvers. */
export function OvertimeFacts({ detail, showEmployee }: { detail: OvertimeDetail; showEmployee?: boolean }) {
  const { t } = useTranslation();
  const o = detail.overtime;
  const r = detail.recorded;
  return (
    <dl className="facts">
      {showEmployee && <><dt>{t('correction.employee')}</dt><dd>{o.employeeName} ({o.employeeNo})</dd></>}
      <dt>{t('overtime.date')}</dt><dd>{formatDate(o.date, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })}</dd>
      <dt>{t('overtime.asked')}</dt><dd>{overtimeText(t, o)}</dd>
      {o.dayKind && <><dt>{t('overtime.dayKindLabel')}</dt><dd>{t(`overtime.dayKind.${o.dayKind}`)}</dd></>}
      {detail.timeOff && <><dt>{t('overtime.timeOff')}</dt><dd><TimeOffText off={detail.timeOff} /></dd></>}
      <dt>{t('overtime.clockIns')}</dt><dd>{sessionsText(r.sessions, t('correction.none'))}</dd>
      <dt>{t('overtime.worked')}</dt><dd>{worked(t, r.workedMin)}</dd>
      {Number(r.otherHours) > 0 && <><dt>{t('overtime.otherHours')}</dt><dd>{num(r.otherHours)}</dd></>}
      <dt>{t('overtime.reason')}</dt><dd>{o.reason}</dd>
      <dt>{t('req.submitted')}</dt><dd>{relative(o.createdAt)}</dd>
    </dl>
  );
}

/** One of your requests: its facts, approval chain, and withdraw / change. */
function OvertimePanel({ id, onEdit, onClose }: { id: string; onEdit: (o: OvertimeRequest) => void; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const detail = useQuery({ queryKey: ['attendance', 'overtime', id], queryFn: () => api.attendance.overtime.detail(id) });
  const cancel = useMutation({
    mutationFn: () => api.attendance.overtime.cancel(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['attendance'] });
      await queryClient.invalidateQueries({ queryKey: ['approvals'] });
      onClose();
    },
  });
  const o = detail.data?.overtime;
  const error = detail.error ?? cancel.error;

  return (
    <Panel title={o ? t('overtime.detailTitle', { date: dayLabel(o.date) }) : t('overtime.mine')} onClose={onClose}>
      {error && <p className="form-error" role="alert">{errorText(error)}</p>}
      {o && (
        <>
          {o.status === 'RETURNED' && o.returnedComment && <p className="notice">{t('correction.sentBack', { comment: o.returnedComment })}</p>}
          <div><CorrectionStatusChip status={o.status} /></div>
          <OvertimeFacts detail={detail.data!} />
          {detail.data!.timeline.length > 0 && (
            <section className="stack">
              <h3 className="panel-subhead">{t('req.chain')}</h3>
              <Timeline steps={detail.data!.timeline} />
            </section>
          )}
          {(o.canEdit || o.canCancel) && (
            <div className="panel-actions">
              {o.canEdit && <button type="button" className="btn primary" onClick={() => onEdit(o)}>{t('correction.edit')}</button>}
              {o.canCancel && (
                <button type="button" className="btn reject" onClick={() => cancel.mutate()} disabled={cancel.isPending}>{t('correction.withdraw')}</button>
              )}
            </div>
          )}
        </>
      )}
    </Panel>
  );
}
