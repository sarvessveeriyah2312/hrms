import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { InboxItem } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { formatDays, formatRange, relative, toTone } from '../../lib/dates';
import { moduleMeta } from '../../lib/modules';
import { Glyph } from '../../components/Glyph';
import { Card, EmptyState, List, PageHeader, Status } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Timeline } from './Timeline';
import { AttachmentButton } from '../../components/app/files';
import { useInbox } from './useInbox';


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

      <Card title={t('approvals.waitingForYou')} count={mine.length}>
        {inbox.data && mine.length === 0 ? (
          <EmptyState icon="inbox" tone="green" title={t('approvals.empty')}>{t('approvals.emptyBody')}</EmptyState>
        ) : (
          <List>{mine.map((i) => <InboxRow key={i.instanceId} item={i} onView={() => setViewing(i)} />)}</List>
        )}
      </Card>

      {others.length > 0 && (
        <Card title={t('approvals.waitingForOthers')} count={others.length}>
          <p className="muted card-pad">{t('approvals.othersHint')}</p>
          <List>{others.map((i) => <InboxRow key={i.instanceId} item={i} onView={() => setViewing(i)} />)}</List>
        </Card>
      )}

      {viewing && <RequestPanel item={viewing} onClose={() => setViewing(null)} />}
    </>
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
      await queryClient.invalidateQueries({ queryKey: ['approvals'] });
      await queryClient.invalidateQueries({ queryKey: ['leave'] });
      onDone?.();
    },
  });
}

function InboxRow({ item, onView }: { item: InboxItem; onView: () => void }) {
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
  const [comment, setComment] = useState('');
  const decide = useDecide(onClose);
  const r = detail.data?.request;
  const b = balance.data?.find((x) => x.leaveTypeId === r?.leaveTypeId);

  return (
    <Panel title={item.title} onClose={onClose}>
      {detail.isError && <p className="form-error" role="alert">{errorText(detail.error)}</p>}
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
      {detail.data && (
        <section className="stack">
          <h3 className="panel-subhead">{t('req.chain')}</h3>
          <Timeline steps={detail.data.timeline} />
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
