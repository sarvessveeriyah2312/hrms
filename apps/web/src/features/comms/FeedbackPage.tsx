import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { FileMeta, GrievanceCategory, GrievanceStatus } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText, useAccess } from '../../lib/access';
import { formatDate, relative } from '../../lib/dates';
import { Card, EmptyState, List, PageHeader, Row, Status, Tabs, Toggle, type StatusTone } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Glyph } from '../../components/Glyph';
import { Icon } from '../../components/Icon';
import { FileField } from '../../components/app/files';
import { openBlob } from '../payroll/payrollText';
import { FilterBar } from '../../components/app/FilterBar';
import { useUrlFilters } from '../../lib/filters';
import './comms.css';

const CATEGORIES: GrievanceCategory[] = ['WORKPLACE', 'HARASSMENT', 'PAY', 'MANAGEMENT', 'FACILITIES', 'SUGGESTION', 'OTHER'];
const TONE: Record<GrievanceStatus, StatusTone> = { OPEN: 'amber', IN_PROGRESS: 'blue', RESOLVED: 'green', CLOSED: 'grey' };

/** Grievances and feedback: raise and follow your own; handlers (comms:grievances) see and answer everyone's. */
export function FeedbackPage() {
  const { t } = useTranslation();
  const { user, can } = useAccess();
  const [params, setParams] = useSearchParams();
  const handler = can('comms:grievances');
  const tabs = [
    ...(user?.employeeId ? [{ key: 'mine', label: t('comms.feedback.mine') }] : []),
    ...(handler ? [{ key: 'all', label: t('comms.feedback.all') }] : []),
  ];
  const tab = tabs.find((x) => x.key === params.get('tab'))?.key ?? tabs[0]?.key ?? 'mine';
  const caseId = params.get('case');
  const setParam = (k: string, v: string | null) => setParams((p) => { const n = new URLSearchParams(p); if (v) n.set(k, v); else n.delete(k); return n; }, { replace: true });
  const [raising, setRaising] = useState(false);

  return (
    <>
      <PageHeader title={t('nav.feedback')} subtitle={t('comms.feedback.subtitle')}
        actions={user?.employeeId && <button type="button" className="btn primary" onClick={() => setRaising(true)}><Icon name="plus" size={16} /> {t('comms.feedback.raise')}</button>} />
      <Tabs tabs={tabs} value={tab} onChange={(k) => setParams(k === tabs[0]?.key ? {} : { tab: k }, { replace: true })} label={t('nav.feedback')} titled>
        {tab === 'mine' && <CaseList mine onOpen={(id) => setParam('case', id)} />}
        {tab === 'all' && <CaseList mine={false} onOpen={(id) => setParam('case', id)} />}
      </Tabs>
      {raising && <RaisePanel onClose={() => setRaising(false)} onDone={(id) => { setRaising(false); setParam('case', id); }} />}
      {caseId && <CasePanel id={caseId} handler={handler} onClose={() => setParam('case', null)} />}
    </>
  );
}

const STATUS_FILTERS = ['ACTIVE', 'OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'ALL'] as const;

/**
 * Your own cases, or (handlers) everyone's: filtered by status on the server, and by category and words (subject or
 * who raised it) in the browser. The filters live in the URL, so a copied link shows the same list.
 */
function CaseList({ mine, onOpen }: { mine: boolean; onOpen: (id: string) => void }) {
  const { t } = useTranslation();
  const filters = useUrlFilters([
    { key: 'status', label: t('comms.feedback.filter'), kind: 'select', pinned: true, defaultValue: 'ACTIVE',
      options: STATUS_FILTERS.map((s) => ({ value: s, label: t(`comms.feedback.statusFilter.${s}`) })) },
    { key: 'category', label: t('comms.feedback.category'), kind: 'select', pinned: true, allLabel: t('common.all'),
      options: CATEGORIES.map((c) => ({ value: c, label: t(`comms.category.${c}`) })) },
    { key: 'q', label: t('comms.feedback.search'), kind: 'search', pinned: true, placeholder: t('comms.feedback.search') },
  ]);
  const status = STATUS_FILTERS.find((s) => s === filters.values.status) ?? 'ACTIVE';
  const cases = useQuery({
    queryKey: ['grievances', mine ? 'mine' : `status:${status}`],
    queryFn: () => (mine ? api.grievances.mine() : api.grievances.all(status)),
  });
  if (mine && cases.data && cases.data.length === 0) {
    return <EmptyState icon="message" tone="orange" title={t('comms.feedback.noneMine')}>{t('comms.feedback.noneMineHint')}</EmptyState>;
  }
  const words = filters.values.q.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const shown = (cases.data ?? []).filter((c) => mine || (
    (!filters.values.category || c.category === filters.values.category)
    && words.every((w) => `${c.subject} ${c.raisedBy} ${c.raisedByNo ?? ''}`.toLowerCase().includes(w))
  ));
  return (
    <Card title={mine ? undefined : t('comms.feedback.all')} count={mine || !cases.data ? undefined : shown.length}>
      {!mine && <div className="card-pad"><FilterBar filters={filters} label={t('comms.feedback.filters')} /></div>}
      {cases.isError && <p className="form-error" role="alert">{errorText(cases.error)}</p>}
      <List>
        {shown.map((c) => (
          <Row key={c.id} onClick={() => onOpen(c.id)} lead={<Glyph icon="message" tone="orange" />} title={c.subject}
            meta={[t(`comms.category.${c.category}`), !mine && c.raisedBy, relative(c.updatedAt), c.assignedName && t('comms.feedback.with', { name: c.assignedName })]
              .filter(Boolean).join(' · ')}
            trail={<Status tone={TONE[c.status]}>{t(`comms.feedback.status.${c.status}`)}</Status>} />
        ))}
        {cases.data && shown.length === 0 && <Row title={t('comms.feedback.noneHere')} />}
        {cases.isPending && <Row title={t('common.loading')} />}
      </List>
    </Card>
  );
}

function RaisePanel({ onClose, onDone }: { onClose: () => void; onDone: (id: string) => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [category, setCategory] = useState<GrievanceCategory>('WORKPLACE');
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [file, setFile] = useState<FileMeta | null>(null);
  const [anonymous, setAnonymous] = useState(false);
  const submit = useMutation({
    mutationFn: () => api.grievances.submit({ category, subject, body, fileId: file?.id ?? null, anonymous }),
    onSuccess: async (d) => { await queryClient.invalidateQueries({ queryKey: ['grievances'] }); onDone(d.grievance.id); },
  });
  return (
    <Panel title={t('comms.feedback.raise')} onClose={onClose}>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); submit.mutate(); }}>
        <p className="hint">{t('comms.feedback.raiseIntro')}</p>
        {submit.error && <p className="form-error" role="alert">{errorText(submit.error)}</p>}
        <label className="field">
          {t('comms.feedback.category')}
          <select value={category} onChange={(e) => setCategory(e.target.value as GrievanceCategory)}>
            {CATEGORIES.map((c) => <option key={c} value={c}>{t(`comms.category.${c}`)}</option>)}
          </select>
        </label>
        <label className="field">
          {t('comms.feedback.subject')}
          <input value={subject} onChange={(e) => setSubject(e.target.value)} maxLength={150} required />
        </label>
        <label className="field">
          {t('comms.feedback.what')}
          <textarea value={body} onChange={(e) => setBody(e.target.value)} maxLength={5000} required rows={6} />
        </label>
        <FileField label={t('comms.feedback.attachment')} current={null} onChange={setFile} />
        <Toggle checked={anonymous} onChange={setAnonymous} label={t('comms.feedback.anonymous')} hint={t('comms.feedback.anonymousHint')} />
        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={submit.isPending || !subject.trim() || !body.trim()}>{t('comms.feedback.send')}</button>
          <button type="button" className="btn" onClick={onClose}>{t('common.cancel')}</button>
        </div>
      </form>
    </Panel>
  );
}

function CasePanel({ id, handler, onClose }: { id: string; handler: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const detail = useQuery({ queryKey: ['grievances', 'one', id], queryFn: () => api.grievances.detail(id) });
  const g = detail.data?.grievance;
  const asHandler = g?.role === 'HANDLER';
  const handlers = useQuery({ queryKey: ['grievances', 'handlers'], queryFn: () => api.grievances.handlers(), enabled: handler && asHandler });
  const [reply, setReply] = useState('');
  const [file, setFile] = useState<FileMeta | null>(null);
  const [fileKey, setFileKey] = useState(0);
  const [note, setNote] = useState('');
  const [reopenReason, setReopenReason] = useState('');
  const [fileError, setFileError] = useState<string | null>(null);
  const update = (d: Awaited<ReturnType<typeof api.grievances.detail>>) => {
    queryClient.setQueryData(['grievances', 'one', id], d);
    void queryClient.invalidateQueries({ queryKey: ['grievances'], exact: false, predicate: (q) => q.queryKey[1] !== 'one' });
  };
  const send = useMutation({
    mutationFn: () => api.grievances.reply(id, reply, file?.id ?? null),
    onSuccess: (d) => { setReply(''); setFile(null); setFileKey((k) => k + 1); update(d); },
  });
  const status = useMutation({ mutationFn: (s: GrievanceStatus) => api.grievances.setStatus(id, s, note || undefined), onSuccess: (d) => { setNote(''); update(d); } });
  const assign = useMutation({ mutationFn: (who: string | null) => api.grievances.assign(id, who), onSuccess: update });
  const close = useMutation({ mutationFn: () => api.grievances.close(id), onSuccess: update });
  const reopen = useMutation({ mutationFn: () => api.grievances.reopen(id, reopenReason), onSuccess: (d) => { setReopenReason(''); update(d); } });
  const error = detail.error ?? send.error ?? status.error ?? assign.error ?? close.error ?? reopen.error;

  return (
    <Panel title={g?.subject ?? t('common.loading')} onClose={onClose}>
      {error && <p className="form-error" role="alert">{errorText(error)}</p>}
      {g && (
        <>
          <p className="case-meta">
            <Status tone={TONE[g.status]}>{t(`comms.feedback.status.${g.status}`)}</Status>
            <span className="hint">
              {[t(`comms.category.${g.category}`), asHandler && t('comms.feedback.raisedBy', { name: g.raisedBy }), g.anonymous && !asHandler && t('comms.feedback.youAreAnonymous'),
                formatDate(g.createdAt, { day: 'numeric', month: 'short', year: 'numeric' })].filter(Boolean).join(' · ')}
            </span>
          </p>
          <ol className="case-thread">
            {detail.data!.messages.map((m) => (
              <li key={m.id} className={m.authorRole === 'SYSTEM' ? 'case-msg case-msg--system' : m.mine ? 'case-msg case-msg--mine' : 'case-msg'}>
                <span className="case-msg__who">{m.mine ? t('comms.feedback.you') : m.author} · {relative(m.createdAt)}</span>
                <span className="case-msg__body">{m.body}</span>
                {m.file && (
                  <button type="button" className="link-button" onClick={() => void openBlob(() => api.grievances.file(id, m.file!.id), setFileError)}>
                    <Icon name="file" size={14} /> {m.file.name}
                  </button>
                )}
              </li>
            ))}
          </ol>
          {fileError && <p className="form-error" role="alert">{fileError}</p>}

          {g.status !== 'CLOSED' && (
            <form className="stack" onSubmit={(e) => { e.preventDefault(); send.mutate(); }}>
              <label className="field">
                {t('comms.feedback.reply')}
                <textarea value={reply} onChange={(e) => setReply(e.target.value)} maxLength={5000} rows={3} />
              </label>
              <FileField key={fileKey} label={t('comms.feedback.attachment')} current={null} onChange={setFile} />
              <div className="panel-actions">
                <button type="submit" className="btn primary" disabled={!reply.trim() || send.isPending}>{t('comms.feedback.sendReply')}</button>
                {!asHandler && <button type="button" className="btn" onClick={() => close.mutate()} disabled={close.isPending}>{t('comms.feedback.close')}</button>}
              </div>
            </form>
          )}

          {!asHandler && g.canReopen && (
            <form className="stack" onSubmit={(e) => { e.preventDefault(); reopen.mutate(); }}>
              <label className="field">
                {t('comms.feedback.reopenWhy')}
                <input value={reopenReason} onChange={(e) => setReopenReason(e.target.value)} maxLength={2000} />
              </label>
              <div className="panel-actions">
                <button type="submit" className="btn" disabled={!reopenReason.trim() || reopen.isPending}>{t('comms.feedback.reopen')}</button>
              </div>
            </form>
          )}

          {asHandler && (
            <section className="stack case-handle">
              <h3 className="panel-subhead">{t('comms.feedback.handle')}</h3>
              <label className="field">
                {t('comms.feedback.assignedTo')}
                <select value={g.assignedTo ?? ''} onChange={(e) => assign.mutate(e.target.value || null)} disabled={assign.isPending}>
                  <option value="">{t('comms.feedback.nobody')}</option>
                  {handlers.data?.map((h) => <option key={h.employeeId} value={h.employeeId}>{h.name}</option>)}
                </select>
              </label>
              <label className="field">
                <span className="field-label">{t('comms.feedback.note')}<span className="field-optional">{t('common.optional')}</span></span>
                <input value={note} onChange={(e) => setNote(e.target.value)} maxLength={2000} placeholder={t('comms.feedback.noteHint')} />
              </label>
              <div className="panel-actions">
                {(['IN_PROGRESS', 'RESOLVED', 'CLOSED'] as const).filter((s) => s !== g.status).map((s) => (
                  <button key={s} type="button" className="btn small" disabled={status.isPending} onClick={() => status.mutate(s)}>
                    {t(`comms.feedback.mark.${s}`)}
                  </button>
                ))}
              </div>
            </section>
          )}
        </>
      )}
    </Panel>
  );
}

