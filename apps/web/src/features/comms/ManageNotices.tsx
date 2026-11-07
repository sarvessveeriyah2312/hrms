import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { FileMeta, Notice, NoticeAudience, NoticeInput, NoticeKind } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText, useAccess } from '../../lib/access';
import { formatDate, relative } from '../../lib/dates';
import { Card, Status, Toggle, type StatusTone } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Table } from '../../components/app/Table';
import { FileField } from '../../components/app/files';

const STATUS_TONE: Record<Notice['status'], StatusTone> = { DRAFT: 'grey', PUBLISHED: 'green', ARCHIVED: 'grey' };

/** Write, publish and archive notices of the kinds you manage, and see who has read (or acknowledged) each. */
export function ManageNotices() {
  const { t } = useTranslation();
  const { can } = useAccess();
  const kinds: NoticeKind[] = [...(can('comms:post') ? ['ANNOUNCEMENT', 'MEMO'] as NoticeKind[] : []), ...(can('comms:policies') ? ['POLICY'] as NoticeKind[] : [])];
  const list = useQuery({ queryKey: ['notices', 'managed'], queryFn: () => api.notices.managed() });
  const [editing, setEditing] = useState<Notice | NoticeKind | null>(null);
  const [receipts, setReceipts] = useState<Notice | null>(null);

  return (
    <Card title={t('comms.manage.title')} count={list.data?.length}
      action={(
        <span className="comms-new">
          {kinds.map((k) => (
            <button key={k} type="button" className="btn small" onClick={() => setEditing(k)}>{t(`comms.manage.new.${k}`)}</button>
          ))}
        </span>
      )}>
      {list.isError && <p className="form-error" role="alert">{errorText(list.error)}</p>}
      <Table
        rows={list.data ?? []}
        rowKey={(n) => n.id}
        label={t('comms.manage.title')}
        loading={list.isPending}
        empty={t('comms.manage.empty')}
        columns={[
          { key: 'title', header: t('comms.manage.col.title'), rowHeader: true,
            cell: (n) => <><button type="button" className="link-button" onClick={() => setEditing(n)}>{n.title}</button><small>{t(`comms.kind.${n.kind}`)}{n.pinned ? ` · ${t('comms.manage.pinned')}` : ''}</small></> },
          { key: 'audience', header: t('comms.manage.col.audience'),
            cell: (n) => n.audience === 'ALL' ? t('comms.audience.ALL') : n.audienceNames.join(', ') },
          { key: 'status', header: t('comms.manage.col.status'),
            cell: (n) => <><Status tone={STATUS_TONE[n.status]}>{t(`comms.status.${n.status}`)}</Status>{n.publishedAt && <small>{relative(n.publishedAt)}{n.version > 1 ? ` · ${t('comms.versionN', { n: n.version })}` : ''}</small>}</> },
          { key: 'reads', header: t('comms.manage.col.reads'), align: 'end',
            cell: (n) => n.audienceSize == null ? '—' : (
              <button type="button" className="link-button" onClick={() => setReceipts(n)}>
                {n.requiresAck ? t('comms.manage.acked', { done: n.acknowledgedCount, total: n.audienceSize }) : t('comms.manage.read', { done: n.readCount, total: n.audienceSize })}
              </button>
            ) },
        ]}
      />
      {editing && <NoticeEditor notice={typeof editing === 'string' ? null : editing} kind={typeof editing === 'string' ? editing : editing.kind} onClose={() => setEditing(null)} />}
      {receipts && <ReceiptsPanel notice={receipts} onClose={() => setReceipts(null)} />}
    </Card>
  );
}

function NoticeEditor({ notice, kind, onClose }: { notice: Notice | null; kind: NoticeKind; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const audiences = useQuery({ queryKey: ['notices', 'audiences'], queryFn: () => api.notices.audiences() });
  const [form, setForm] = useState<NoticeInput>(() => ({
    kind, title: notice?.title ?? '', summary: notice?.summary ?? '', body: notice?.body ?? '', audience: notice?.audience ?? 'ALL',
    audienceIds: notice?.audienceIds ?? [], attachmentIds: notice?.attachments.map((a) => a.id) ?? [], pinned: notice?.pinned ?? false,
    requiresAck: notice?.requiresAck ?? kind === 'POLICY',
  }));
  const [files, setFiles] = useState<FileMeta[]>(notice?.attachments ?? []);
  const [adding, setAdding] = useState(0);
  const [confirm, setConfirm] = useState<'delete' | 'archive' | null>(null);
  const set = <K extends keyof NoticeInput>(k: K, v: NoticeInput[K]) => setForm((f) => ({ ...f, [k]: v }));
  const done = async () => {
    await queryClient.invalidateQueries({ queryKey: ['notices'] });
  };
  const input = (): NoticeInput => ({ ...form, attachmentIds: files.map((f) => f.id) });
  const save = useMutation({
    mutationFn: async (publish: boolean) => {
      const saved = notice ? await api.notices.update(notice.id, input()) : await api.notices.create(input());
      return publish ? api.notices.publish(saved.id) : saved;
    },
    onSuccess: async () => { await done(); onClose(); },
  });
  const act = useMutation({
    mutationFn: (what: 'delete' | 'archive') => (what === 'delete' ? api.notices.remove(notice!.id) : api.notices.archive(notice!.id)),
    onSuccess: async () => { await done(); onClose(); },
  });
  const published = notice?.status === 'PUBLISHED';
  const groups = form.audience === 'DEPARTMENTS' ? audiences.data?.departments : audiences.data?.entities;
  const title = notice ? t('comms.manage.editTitle', { title: notice.title }) : t(`comms.manage.new.${kind}`);

  return (
    <Panel title={title} onClose={onClose}>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); save.mutate(false); }}>
        {(save.error || act.error) && <p className="form-error" role="alert">{errorText(save.error ?? act.error)}</p>}
        {published && kind === 'POLICY' && <p className="hint">{t('comms.manage.policyVersionHint')}</p>}
        {published && kind !== 'POLICY' && <p className="hint">{t('comms.manage.liveHint')}</p>}
        <label className="field">
          {t('comms.manage.titleField')}
          <input value={form.title} onChange={(e) => set('title', e.target.value)} maxLength={150} required />
        </label>
        <label className="field">
          <span className="field-label">{t('comms.manage.summary')}<span className="field-optional">{t('common.optional')}</span></span>
          <input value={form.summary ?? ''} onChange={(e) => set('summary', e.target.value)} maxLength={300} placeholder={t('comms.manage.summaryHint')} />
        </label>
        <label className="field">
          {t('comms.manage.body')}
          <textarea className="notice-editor__body" value={form.body} onChange={(e) => set('body', e.target.value)} maxLength={50000}
            placeholder={t('comms.manage.bodyHint')} />
        </label>
        <fieldset>
          <legend>{t('comms.manage.who')}</legend>
          <div className="field-row">
            <label className="field">
              {t('comms.manage.audience')}
              <select value={form.audience} onChange={(e) => { set('audience', e.target.value as NoticeAudience); set('audienceIds', []); }}>
                {(['ALL', 'DEPARTMENTS', 'ENTITIES'] as const).map((a) => <option key={a} value={a}>{t(`comms.audience.${a}`)}</option>)}
              </select>
            </label>
          </div>
          {form.audience !== 'ALL' && (
            <div className="comms-groups" role="group" aria-label={t(`comms.audience.${form.audience}`)}>
              {(groups ?? []).map((g) => (
                <label key={g.id} className="comms-group">
                  <input type="checkbox" checked={form.audienceIds.includes(g.id)}
                    onChange={(e) => set('audienceIds', e.target.checked ? [...form.audienceIds, g.id] : form.audienceIds.filter((x) => x !== g.id))} />
                  {g.name}
                </label>
              ))}
            </div>
          )}
        </fieldset>
        <fieldset>
          <legend>{t('comms.attachments')}</legend>
          {files.map((f) => (
            <div key={f.id} className="file-chip">
              <span className="file-chip__name">{f.name}</span>
              <button type="button" className="link-button" onClick={() => setFiles((x) => x.filter((y) => y.id !== f.id))}>{t('common.remove')}</button>
            </div>
          ))}
          {files.length < 5 && (
            <FileField key={adding} label={t('comms.manage.addAttachment')} current={null}
              onChange={(f) => { if (f) { setFiles((x) => [...x, f]); setAdding((n) => n + 1); } }} />
          )}
        </fieldset>
        <Toggle checked={form.pinned} onChange={(v) => set('pinned', v)} label={t('comms.manage.pin')} hint={t('comms.manage.pinHint')} />
        <Toggle checked={form.requiresAck} onChange={(v) => set('requiresAck', v)} label={t('comms.manage.ack')} hint={t('comms.manage.ackHint')} />
        <div className="panel-actions">
          {!published && <button type="button" className="btn primary" disabled={save.isPending} onClick={() => save.mutate(true)}>{t('comms.manage.publish')}</button>}
          {published && kind === 'POLICY' && (
            <button type="button" className="btn primary" disabled={save.isPending} onClick={() => save.mutate(true)}>{t('comms.manage.publishVersion')}</button>
          )}
          <button type="submit" className="btn" disabled={save.isPending}>{published ? t('common.saveChanges') : t('comms.manage.saveDraft')}</button>
          {notice?.status === 'DRAFT' && (confirm === 'delete'
            ? <button type="button" className="btn reject" onClick={() => act.mutate('delete')}>{t('comms.manage.confirmDelete')}</button>
            : <button type="button" className="btn reject" onClick={() => setConfirm('delete')}>{t('common.delete')}</button>)}
          {published && (confirm === 'archive'
            ? <button type="button" className="btn reject" onClick={() => act.mutate('archive')}>{t('comms.manage.confirmArchive')}</button>
            : <button type="button" className="btn" onClick={() => setConfirm('archive')}>{t('comms.manage.archive')}</button>)}
        </div>
      </form>
    </Panel>
  );
}

function ReceiptsPanel({ notice, onClose }: { notice: Notice; onClose: () => void }) {
  const { t } = useTranslation();
  const receipts = useQuery({ queryKey: ['notices', 'receipts', notice.id], queryFn: () => api.notices.receipts(notice.id) });
  const [show, setShow] = useState<'pending' | 'all'>('pending');
  const people = receipts.data?.people ?? [];
  const pending = people.filter((p) => (notice.requiresAck ? !p.acknowledgedAt : !p.readAt));
  const rows = show === 'pending' ? pending : people;
  const day = (iso: string | null) => (iso ? formatDate(iso, { day: 'numeric', month: 'short' }) : '—');

  return (
    <Panel title={t('comms.receipts.title', { title: notice.title })} onClose={onClose}>
      {receipts.error && <p className="form-error" role="alert">{errorText(receipts.error)}</p>}
      <p className="hint">
        {notice.requiresAck
          ? t('comms.receipts.ackSummary', { pending: pending.length, total: people.length, version: receipts.data?.version ?? notice.version })
          : t('comms.receipts.readSummary', { pending: pending.length, total: people.length })}
      </p>
      <div className="panel-actions">
        <button type="button" className={show === 'pending' ? 'btn small primary' : 'btn small'} onClick={() => setShow('pending')}>
          {notice.requiresAck ? t('comms.receipts.notAcked') : t('comms.receipts.notRead')} ({pending.length})
        </button>
        <button type="button" className={show === 'all' ? 'btn small primary' : 'btn small'} onClick={() => setShow('all')}>{t('common.all')} ({people.length})</button>
      </div>
      <Table rows={rows} rowKey={(p) => p.employeeId} label={t('comms.receipts.title', { title: notice.title })} loading={receipts.isPending}
        empty={t('comms.receipts.everyone')} pageSizes={[]} pageSize={50}
        columns={[
          { key: 'name', header: t('payroll.col.person'), rowHeader: true, cell: (p) => <>{p.name}<small>{[p.employeeNo, p.department].filter(Boolean).join(' · ')}</small></> },
          { key: 'read', header: t('comms.receipts.read'), cell: (p) => day(p.readAt) },
          ...(notice.requiresAck ? [{ key: 'ack', header: t('comms.receipts.acked'), cell: (p: { acknowledgedAt: string | null }) => day(p.acknowledgedAt) }] : []),
        ]} />
    </Panel>
  );
}
