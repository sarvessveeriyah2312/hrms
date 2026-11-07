import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { InboxItem } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { formatDays, formatRange, relative, toTone } from '../../lib/dates';
import { moduleMeta } from '../../lib/modules';
import { Glyph } from '../../components/Glyph';
import { Card, EmptyState, List, PageHeader, Status } from '../../components/app/kit';
import { CorrectionFacts } from '../attendance/Corrections';
import { OvertimeFacts } from '../attendance/Overtime';
import { ShiftChangeFacts } from '../attendance/ShiftChangeFacts';
import { Panel } from '../../components/app/Panel';
import { Pager } from '../../components/app/Pager';
import { usePaging } from '../../lib/paging';
import { Timeline } from './Timeline';
import { AttachmentButton } from '../../components/app/files';
import { useInbox } from './useInbox';
import { ClaimFacts } from '../claims/ClaimsPage';
import { ChangeCompare } from '../profile/ChangeCompare';


/** Approvals inbox: requests waiting for you, and (for company-wide approvers) ones you may step in on. */
export function ApprovalsPage() {
  const { t } = useTranslation();
  const inbox = useInbox();
  const [picked, setPicked] = useState<InboxItem | null>(null);
  const [params, setParams] = useSearchParams();
  const items = inbox.data?.items ?? [];

  // Opened from a notification: /app/approvals?open=<instanceId>
  const openId = params.get('open');
  const fromLink = openId ? items.find((i) => i.instanceId === openId) ?? null : null;
  const gone = !!openId && !!inbox.data && !fromLink;
  const viewing = picked ?? fromLink;
  const clearLink = () => openId && setParams({}, { replace: true });
  const setViewing = (item: InboxItem | null) => {
    setPicked(item);
    clearLink();
  };
  const mine = items.filter((i) => i.actingAs !== 'OVERRIDE');
  const others = items.filter((i) => i.actingAs === 'OVERRIDE');
  const minePaging = usePaging(mine);
  const othersPaging = usePaging(others, { pageSize: 10 });
  // Overtime can be approved several at a time (only what's waiting for you, not ones you'd step in on).
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  const overtime = mine.filter((i) => i.subjectType === 'OVERTIME');
  const selected = overtime.filter((i) => chosen.has(i.instanceId));
  const toggle = (id: string) => setChosen((c) => {
    const next = new Set(c);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  return (
    <>
      <PageHeader title={t('approvals.title')} subtitle={t('approvals.subtitle')} />
      {inbox.isError && <p className="form-error" role="alert">{errorText(inbox.error)}</p>}
      {gone && (
        <p className="notice notice--action" role="status">
          <span>{t('approvals.gone')}</span>
          <button type="button" className="btn small" onClick={clearLink}>{t('common.ok')}</button>
        </p>
      )}

      <Card
        title={t('approvals.waitingForYou')}
        count={mine.length}
        action={overtime.length > 1 && (
          <button type="button" className="btn small" onClick={() => setChosen(new Set(selected.length === overtime.length ? [] : overtime.map((i) => i.instanceId)))}>
            {selected.length === overtime.length ? t('approvals.many.clear') : t('approvals.many.selectAll', { count: overtime.length })}
          </button>
        )}
      >
        {selected.length > 0 && <ApproveMany items={selected} onDone={() => setChosen(new Set())} />}
        {inbox.data && mine.length === 0 ? (
          <EmptyState icon="inbox" tone="green" title={t('approvals.empty')}>{t('approvals.emptyBody')}</EmptyState>
        ) : (
          <>
            <List>{minePaging.visible.map((i) => (
              <InboxRow key={i.instanceId} item={i} onView={() => setViewing(i)}
                selected={i.subjectType === 'OVERTIME' ? chosen.has(i.instanceId) : undefined} onSelect={() => toggle(i.instanceId)} />
            ))}</List>
            <Pager {...minePaging} label={t('approvals.waitingForYou')} />
          </>
        )}
      </Card>

      {others.length > 0 && (
        <Card title={t('approvals.waitingForOthers')} count={others.length}>
          <p className="muted card-pad">{t('approvals.othersHint')}</p>
          <List>{othersPaging.visible.map((i) => <InboxRow key={i.instanceId} item={i} onView={() => setViewing(i)} />)}</List>
          <Pager {...othersPaging} label={t('approvals.waitingForOthers')} />
        </Card>
      )}

      {viewing && <RequestPanel item={viewing} onClose={() => setViewing(null)} />}
    </>
  );
}

/** Everything a decision can change. */
async function refreshAfterDeciding(queryClient: QueryClient) {
  await queryClient.invalidateQueries({ queryKey: ['approvals'] });
  await queryClient.invalidateQueries({ queryKey: ['leave'] });
  await queryClient.invalidateQueries({ queryKey: ['claims'] });
  await queryClient.invalidateQueries({ queryKey: ['attendance'] });
  await queryClient.invalidateQueries({ queryKey: ['profile'] });
}

/** Approve the chosen overtime requests with one optional comment; lists any that couldn't be approved. */
function ApproveMany({ items, onDone }: { items: InboxItem[]; onDone: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [comment, setComment] = useState('');
  const [failed, setFailed] = useState<{ title: string; message: string }[]>([]);
  const approve = useMutation({
    mutationFn: () => api.approvals.approveMany(items.map((i) => i.instanceId), comment.trim() || undefined),
    onSuccess: async (result) => {
      setFailed(result.failed.map((f) => ({ title: items.find((i) => i.instanceId === f.instanceId)?.title ?? '', message: f.message })));
      setComment('');
      await refreshAfterDeciding(queryClient);
      if (result.failed.length === 0) onDone();
    },
  });

  return (
    <form className="inbox-many" onSubmit={(e) => { e.preventDefault(); approve.mutate(); }}>
      <p className="inbox-many__count" role="status">
        {t('approvals.many.chosen', { count: items.length })}
      </p>
      {approve.error && <p className="form-error" role="alert">{errorText(approve.error)}</p>}
      {failed.length > 0 && (
        <div className="form-error" role="alert">
          <p>{t('approvals.many.someFailed', { count: failed.length })}</p>
          <ul>{failed.map((f, n) => <li key={n}>{f.title}: {f.message}</li>)}</ul>
        </div>
      )}
      <div className="inbox-many__row">
        <label className="field">
          {t('approvals.comment')}
          <input value={comment} onChange={(e) => setComment(e.target.value)} maxLength={500} placeholder={t('approvals.many.commentHint')} />
        </label>
        <button type="submit" className="btn primary small" disabled={approve.isPending}>
          {t('approvals.many.approve', { count: items.length })}
        </button>
        <button type="button" className="btn small" onClick={onDone} disabled={approve.isPending}>{t('approvals.many.clear')}</button>
      </div>
    </form>
  );
}

function useDecide(onDone?: () => void) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (d: { item: InboxItem; action: 'approve' | 'reject' | 'return'; comment?: string }) =>
      d.action === 'approve' ? api.approvals.approve(d.item.instanceId, d.comment)
        : d.action === 'return' ? api.approvals.sendBack(d.item.instanceId, d.comment ?? '')
          : api.approvals.reject(d.item.instanceId, d.comment ?? ''),
    onSuccess: async () => {
      await refreshAfterDeciding(queryClient);
      onDone?.();
    },
  });
}

/** selected: shown with a checkbox to approve it along with others (overtime); undefined: no checkbox. */
function InboxRow({ item, onView, selected, onSelect }: {
  item: InboxItem; onView: () => void; selected?: boolean; onSelect?: () => void;
}) {
  const { t } = useTranslation();
  const [mode, setMode] = useState<'reject' | 'return' | null>(null);
  const [reason, setReason] = useState('');
  const decide = useDecide();
  const meta = moduleMeta(item.module);
  const who = item.actingAs === 'OVERRIDE' ? t('approvals.waitingFor', { name: item.waitingFor })
    : item.actingAs === 'BACKUP' ? t('approvals.asBackup') : item.actingAs === 'HR' ? t('approvals.asHr') : '';

  return (
    <li className="inbox-row">
      <div className="ui-row">
        {selected !== undefined && (
          <input type="checkbox" className="inbox-row__pick" checked={selected} onChange={onSelect}
            aria-label={t('approvals.many.pick', { title: item.title, who: item.requester })} />
        )}
        <span className="ui-row__lead"><Glyph icon={meta.icon} tone={toTone(item.tone, meta.tone)} /></span>
        <span className="ui-row__text">
          <span className="ui-row__title">{item.title}</span>
          <span className="ui-row__meta">
            {[item.requester, relative(item.submittedAt), item.stepLabel, item.detail].filter(Boolean).join(' · ')}
          </span>
          {who && <span className="ui-row__meta inbox-row__who">{who}</span>}
        </span>
        <span className="ui-row__trail">
          {item.overdue && <Status tone="red">{t('dashboard.overdue')}</Status>}
          <button type="button" className="btn small" onClick={onView}>{t('common.view')}</button>
          <button type="button" className="btn small" onClick={() => setMode((m) => (m === 'return' ? null : 'return'))} aria-expanded={mode === 'return'}>{t('approvals.sendBack')}</button>
          <button type="button" className="btn small" onClick={() => setMode((m) => (m === 'reject' ? null : 'reject'))} aria-expanded={mode === 'reject'}>{t('approvals.reject')}</button>
          <button
            type="button"
            className="btn small primary"
            disabled={decide.isPending}
            onClick={() => decide.mutate({ item, action: 'approve' })}
            aria-label={t('preview.approveLabel', { title: item.title })}
          >
            {t('approvals.approve')}
          </button>
        </span>
      </div>
      {(mode || decide.error) && (
        <form
          className="inbox-row__reject"
          onSubmit={(e) => {
            e.preventDefault();
            if (mode) decide.mutate({ item, action: mode, comment: reason });
          }}
        >
          {decide.error && <p className="form-error" role="alert">{errorText(decide.error)}</p>}
          {mode && (
            <>
              <label className="field">
                {mode === 'return' ? t('approvals.whatChange') : t('approvals.whyReject')}
                <input value={reason} onChange={(e) => setReason(e.target.value)} autoFocus required maxLength={500}
                  placeholder={mode === 'return' ? t('approvals.changeExample') : t('approvals.theySee')} />
              </label>
              <button type="submit" className={mode === 'reject' ? 'btn small reject' : 'btn small'} disabled={decide.isPending}>
                {mode === 'return' ? t('approvals.sendBackFull') : t('approvals.rejectFull')}
              </button>
            </>
          )}
        </form>
      )}
    </li>
  );
}

/** The full request: dates, days, reason, the requester's balance, and the approval timeline. */
function RequestPanel({ item, onClose }: { item: InboxItem; onClose: () => void }) {
  const { t } = useTranslation();
  const detail = useQuery({
    queryKey: ['leave', 'request', item.subjectId],
    queryFn: () => api.leave.detail(item.subjectId),
    enabled: item.subjectType === 'LEAVE',
  });
  const balance = useQuery({
    queryKey: ['leave', 'balances', detail.data?.request.employeeId],
    queryFn: () => api.leave.balances(detail.data!.request.employeeId),
    enabled: !!detail.data,
  });
  const claim = useQuery({
    queryKey: ['claims', 'detail', item.subjectId],
    queryFn: () => api.claims.detail(item.subjectId),
    enabled: item.subjectType === 'CLAIM',
  });
  const correction = useQuery({
    queryKey: ['attendance', 'correction', item.subjectId],
    queryFn: () => api.attendance.corrections.detail(item.subjectId),
    enabled: item.subjectType === 'CORRECTION',
  });
  const shiftChange = useQuery({
    queryKey: ['attendance', 'shift-request', item.subjectId],
    queryFn: () => api.attendance.shiftRequests.detail(item.subjectId),
    enabled: item.subjectType === 'SHIFT_CHANGE',
  });
  const profileChange = useQuery({
    queryKey: ['profile', 'change', item.subjectId],
    queryFn: () => api.profiles.changes.detail(item.subjectId),
    enabled: item.subjectType === 'PROFILE_CHANGE',
  });
  const overtime = useQuery({
    queryKey: ['attendance', 'overtime', item.subjectId],
    queryFn: () => api.attendance.overtime.detail(item.subjectId),
    enabled: item.subjectType === 'OVERTIME',
  });
  const [comment, setComment] = useState('');
  const decide = useDecide(onClose);
  const r = detail.data?.request;
  const timeline = detail.data?.timeline ?? claim.data?.timeline ?? correction.data?.timeline ?? overtime.data?.timeline ?? shiftChange.data?.timeline ?? profileChange.data?.timeline;
  const loadError = detail.error ?? claim.error ?? correction.error ?? overtime.error ?? shiftChange.error ?? profileChange.error;
  const b = balance.data?.find((x) => x.leaveTypeId === r?.leaveTypeId);

  return (
    <Panel title={item.title} onClose={onClose}>
      {loadError && <p className="form-error" role="alert">{errorText(loadError)}</p>}
      {claim.data && <ClaimFacts claim={claim.data.claim} showEmployee />}
      {correction.data && <CorrectionFacts detail={correction.data} showEmployee />}
      {overtime.data && <OvertimeFacts detail={overtime.data} showEmployee />}
      {shiftChange.data && <ShiftChangeFacts request={shiftChange.data.request} />}
      {profileChange.data && <ChangeCompare detail={profileChange.data} showEmployee />}
      {r && (
        <dl className="facts">
          <dt>{t('req.employee')}</dt><dd>{r.employeeName} ({item.requesterNo})</dd>
          <dt>{t('req.dates')}</dt><dd>{formatRange(r.startDate, r.endDate, r.dayPart)}</dd>
          <dt>{t('req.workingDays')}</dt><dd>{formatDays(r.days)}</dd>
          {b?.available !== null && b && <><dt>{t('req.leftIfApproved')}</dt><dd>{t('req.daysOf', { left: formatDays(b.available ?? 0), total: formatDays(Number(b.entitled) + Number(b.carried)) })}</dd></>}
          <dt>{t('req.reason')}</dt><dd>{r.reason ?? '—'}</dd>
          <dt>{t('req.document')}</dt><dd>{r.attachment ? <AttachmentButton requestId={r.id} file={r.attachment} /> : '—'}</dd>
          <dt>{t('req.submitted')}</dt><dd>{relative(r.createdAt)}</dd>
        </dl>
      )}
      {timeline && (
        <section className="stack">
          <h3 className="panel-subhead">{t('req.chain')}</h3>
          <Timeline steps={timeline} />
        </section>
      )}
      <form
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          decide.mutate({ item, action: 'approve', comment });
        }}
      >
        {decide.error && <p className="form-error" role="alert">{errorText(decide.error)}</p>}
        <label className="field">
          {t('approvals.comment')}
          <textarea value={comment} onChange={(e) => setComment(e.target.value)} maxLength={500} placeholder={t('approvals.commentHint')} />
        </label>
        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={decide.isPending}>{t('approvals.approve')}</button>
          <button type="button" className="btn" disabled={decide.isPending} onClick={() => decide.mutate({ item, action: 'return', comment })}>{t('approvals.sendBack')}</button>
          <button type="button" className="btn reject" disabled={decide.isPending} onClick={() => decide.mutate({ item, action: 'reject', comment })}>{t('approvals.reject')}</button>
        </div>
      </form>
    </Panel>
  );
}
