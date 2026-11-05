import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { AttendanceCorrection, CorrectionDetail, CorrectionStatus, TimesheetDay, WorkSession } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { formatDate, relative } from '../../lib/dates';
import { Card, Status, type StatusTone } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Table } from '../../components/app/Table';
import { Timeline } from '../approvals/Timeline';

const hhmm = (time: string | null | undefined) => (time ? time.slice(0, 5) : '');
const dayLabel = (date: string) => formatDate(date, { weekday: 'short', day: 'numeric', month: 'short' });

const TONE: Record<CorrectionStatus, StatusTone> = {
  PENDING: 'amber', APPROVED: 'green', REJECTED: 'red', RETURNED: 'amber', CANCELLED: 'grey',
};

export function CorrectionStatusChip({ status }: { status: CorrectionStatus }) {
  const { t } = useTranslation();
  return <Status tone={TONE[status]}>{t(`correction.statusName.${status}`)}</Status>;
}

/** "In 09:00 · Out 18:00" */
function correctedTimes(t: (k: string, o?: Record<string, unknown>) => string, c: { clockIn: string | null; clockOut: string | null }) {
  return [c.clockIn && t('correction.in', { time: hhmm(c.clockIn) }), c.clockOut && t('correction.out', { time: hhmm(c.clockOut) })]
    .filter(Boolean).join(' · ');
}

function sessionsText(sessions: WorkSession[], none: string) {
  return sessions.length ? sessions.map((s) => `${hhmm(s.in)}–${s.out ? hhmm(s.out) : '?'}`).join(', ') : none;
}

/** What to open: a new request for a day, or editing one sent back. */
export type CorrectionTarget = { day: TimesheetDay } | { editing: AttendanceCorrection };

/** Ask for a day's first clock-in and/or last clock-out to be corrected, or change one that was sent back. */
export function CorrectionForm({ target, onClose }: { target: CorrectionTarget; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const editing = 'editing' in target ? target.editing : null;
  const day = 'day' in target ? target.day : null;
  const date = editing?.date ?? day!.date;
  const [clockIn, setClockIn] = useState(hhmm(editing ? editing.clockIn : day?.firstIn));
  const [clockOut, setClockOut] = useState(hhmm(editing ? editing.clockOut : day?.missedOut || day?.stillIn ? null : day?.lastOut));
  const [reason, setReason] = useState(editing?.reason ?? '');

  // What the clock-ins showed: from the timesheet day, or loaded for a request being edited.
  const recorded = useQuery({
    queryKey: ['attendance', 'correction', editing?.id],
    queryFn: () => api.attendance.corrections.detail(editing!.id),
    enabled: !!editing,
  });
  const sessions = day?.sessions ?? recorded.data?.recorded.sessions ?? [];

  const submit = useMutation({
    mutationFn: () => {
      const input = { date, clockIn: clockIn || null, clockOut: clockOut || null, reason: reason.trim() };
      return editing ? api.attendance.corrections.resubmit(editing.id, input) : api.attendance.corrections.submit(input);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['attendance'] });
      await queryClient.invalidateQueries({ queryKey: ['approvals'] });
      onClose();
    },
  });
  const valid = (!!clockIn || !!clockOut) && reason.trim().length > 0 && (!clockIn || !clockOut || clockOut > clockIn);

  return (
    <Panel title={t(editing ? 'correction.editTitle' : 'correction.title', { date: dayLabel(date) })} onClose={onClose}>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); submit.mutate(); }}>
        {editing?.returnedComment && <p className="notice">{t('correction.sentBack', { comment: editing.returnedComment })}</p>}
        {submit.error && <p className="form-error" role="alert">{errorText(submit.error)}</p>}
        <dl className="facts">
          <dt>{t('correction.recorded')}</dt><dd>{sessionsText(sessions, t('correction.none'))}</dd>
        </dl>
        <div className="field-row">
          <label className="field">{t('correction.clockIn')}<input type="time" value={clockIn} onChange={(e) => setClockIn(e.target.value)} /></label>
          <label className="field">{t('correction.clockOut')}<input type="time" value={clockOut} onChange={(e) => setClockOut(e.target.value)} /></label>
        </div>
        <p className="hint">{t('correction.timesHint')}</p>
        {clockIn && clockOut && clockOut <= clockIn && <p className="form-error" role="alert">{t('correction.outAfterIn')}</p>}
        <label className="field">
          {t('correction.reason')}
          <textarea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} required placeholder={t('correction.reasonHint')} />
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

/** The facts of one request, for its owner and for approvers. */
export function CorrectionFacts({ detail, showEmployee }: { detail: CorrectionDetail; showEmployee?: boolean }) {
  const { t } = useTranslation();
  const c = detail.correction;
  return (
    <dl className="facts">
      {showEmployee && <><dt>{t('correction.employee')}</dt><dd>{c.employeeName} ({c.employeeNo})</dd></>}
      <dt>{t('correction.date')}</dt><dd>{formatDate(c.date, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })}</dd>
      <dt>{t('correction.recorded')}</dt><dd>{sessionsText(detail.recorded.sessions, t('correction.none'))}</dd>
      <dt>{t('correction.requested')}</dt><dd>{correctedTimes(t, c)}</dd>
      <dt>{t('correction.reason')}</dt><dd>{c.reason}</dd>
      <dt>{t('req.submitted')}</dt><dd>{relative(c.createdAt)}</dd>
    </dl>
  );
}

/** One of your requests: its facts, approval chain, and withdraw / change. */
function CorrectionPanel({ id, onEdit, onClose }: { id: string; onEdit: (c: AttendanceCorrection) => void; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const detail = useQuery({ queryKey: ['attendance', 'correction', id], queryFn: () => api.attendance.corrections.detail(id) });
  const cancel = useMutation({
    mutationFn: () => api.attendance.corrections.cancel(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['attendance'] });
      await queryClient.invalidateQueries({ queryKey: ['approvals'] });
      onClose();
    },
  });
  const c = detail.data?.correction;
  const error = detail.error ?? cancel.error;

  return (
    <Panel title={c ? t('correction.detailTitle', { date: dayLabel(c.date) }) : t('correction.mine')} onClose={onClose}>
      {error && <p className="form-error" role="alert">{errorText(error)}</p>}
      {c && (
        <>
          {c.status === 'RETURNED' && c.returnedComment && <p className="notice">{t('correction.sentBack', { comment: c.returnedComment })}</p>}
          <div><CorrectionStatusChip status={c.status} /></div>
          <CorrectionFacts detail={detail.data!} />
          {detail.data!.timeline.length > 0 && (
            <section className="stack">
              <h3 className="panel-subhead">{t('req.chain')}</h3>
              <Timeline steps={detail.data!.timeline} />
            </section>
          )}
          {(c.canEdit || c.canCancel) && (
            <div className="panel-actions">
              {c.canEdit && <button type="button" className="btn primary" onClick={() => onEdit(c)}>{t('correction.edit')}</button>}
              {c.canCancel && (
                <button type="button" className="btn reject" onClick={() => cancel.mutate()} disabled={cancel.isPending}>{t('correction.withdraw')}</button>
              )}
            </div>
          )}
        </>
      )}
    </Panel>
  );
}

/** My timesheet → My corrections: every request, newest day first; open one to withdraw or change it. */
export function MyCorrections({ openId, onOpen, onEdit }: {
  openId: string | null; onOpen: (id: string | null) => void; onEdit: (c: AttendanceCorrection) => void;
}) {
  const { t } = useTranslation();
  const list = useQuery({ queryKey: ['attendance', 'corrections', 'mine'], queryFn: api.attendance.corrections.mine });

  return (
    <>
      <Card title={t('correction.mine')} count={list.data?.length}>
        {list.isError && <p className="form-error card-pad" role="alert">{errorText(list.error)}</p>}
        <Table
          label={t('correction.mine')}
          rows={list.data ?? []}
          rowKey={(c) => c.id}
          loading={list.isPending}
          empty={t('correction.empty')}
          pageSize={10}
          pageSizes={[]}
          columns={[
            { key: 'date', header: t('correction.date'), rowHeader: true, cell: (c) => (
              <button type="button" className="link-button" onClick={() => onOpen(c.id)}>{dayLabel(c.date)}</button>
            ) },
            { key: 'times', header: t('correction.requested'), cell: (c) => correctedTimes(t, c) },
            { key: 'reason', header: t('correction.reason'), cell: (c) => <span className="muted">{c.reason}</span> },
            { key: 'status', header: t('correction.status'), cell: (c) => (
              <span className="corr-status">
                <CorrectionStatusChip status={c.status} />
                {c.pendingWith && <span className="muted">{t('correction.waitingFor', { name: c.pendingWith.approver })}</span>}
              </span>
            ) },
          ]}
        />
      </Card>
      {openId && <CorrectionPanel id={openId} onEdit={(c) => { onOpen(null); onEdit(c); }} onClose={() => onOpen(null)} />}
    </>
  );
}
