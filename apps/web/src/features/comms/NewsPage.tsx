import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { IssuedLetter, Notice } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText, useAccess } from '../../lib/access';
import { formatDate, relative } from '../../lib/dates';
import { Card, EmptyState, List, PageHeader, Row, Status, Tabs } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Glyph } from '../../components/Glyph';
import { Icon } from '../../components/Icon';
import { openBlob } from '../payroll/payrollText';
import { ManageNotices } from './ManageNotices';
import { LettersAdmin } from './LettersAdmin';
import { NoticeBody } from './NoticeBody';
import './comms.css';

/**
 * News: announcements and HR memos, company policies to read and acknowledge, and your own letters. People who manage
 * notices or letters get the tabs to do it. ?notice=<id> opens one (links from notifications).
 */
export function NewsPage() {
  const { t } = useTranslation();
  const { user, can } = useAccess();
  const [params, setParams] = useSearchParams();
  const manages = can('comms:post') || can('comms:policies');
  const tabs = [
    { key: 'news', label: t('comms.tabs.news') },
    { key: 'policies', label: t('comms.tabs.policies') },
    ...(user?.employeeId ? [{ key: 'letters', label: t('comms.tabs.letters') }] : []),
    ...(manages ? [{ key: 'manage', label: t('comms.tabs.manage') }] : []),
    ...(can('comms:letters') ? [{ key: 'issue', label: t('comms.tabs.issue') }] : []),
  ];
  const tab = tabs.find((x) => x.key === params.get('tab'))?.key ?? 'news';
  const setTab = (key: string) => setParams(key === 'news' ? {} : { tab: key }, { replace: true });
  const openId = params.get('notice');
  const closeNotice = () => setParams((p) => { const n = new URLSearchParams(p); n.delete('notice'); return n; }, { replace: true });
  const openNotice = (id: string) => setParams((p) => { const n = new URLSearchParams(p); n.set('notice', id); return n; }, { replace: true });

  return (
    <>
      <PageHeader title={t('nav.news')} subtitle={t('comms.subtitle')} />
      <Tabs tabs={tabs} value={tab} onChange={setTab} label={t('nav.news')} titled>
        {tab === 'news' && <NoticeList kinds={['ANNOUNCEMENT', 'MEMO']} onOpen={openNotice} />}
        {tab === 'policies' && <NoticeList kinds={['POLICY']} onOpen={openNotice} />}
        {tab === 'letters' && <MyLetters />}
        {tab === 'manage' && <ManageNotices />}
        {tab === 'issue' && <LettersAdmin />}
      </Tabs>
      {openId && <NoticePanel id={openId} onClose={closeNotice} />}
    </>
  );
}

const KIND_ICON = { ANNOUNCEMENT: 'message', MEMO: 'file', POLICY: 'shield' } as const;

function NoticeList({ kinds, onOpen }: { kinds: Notice['kind'][]; onOpen: (id: string) => void }) {
  const { t } = useTranslation();
  const all = useQuery({ queryKey: ['notices', 'mine'], queryFn: () => api.notices.mine() });
  const list = (all.data ?? []).filter((n) => kinds.includes(n.kind));
  const policies = kinds.includes('POLICY');

  if (all.data && list.length === 0) {
    return (
      <EmptyState icon={policies ? 'shield' : 'message'} tone="purple" title={policies ? t('comms.noPolicies') : t('comms.noNews')}>
        {policies ? t('comms.noPoliciesHint') : t('comms.noNewsHint')}
      </EmptyState>
    );
  }
  return (
    <Card>
      {all.isError && <p className="form-error" role="alert">{errorText(all.error)}</p>}
      <List>
        {list.map((n) => (
          <Row key={n.id} onClick={() => onOpen(n.id)}
            lead={<Glyph icon={KIND_ICON[n.kind]} tone={n.kind === 'POLICY' ? 'purple' : n.kind === 'MEMO' ? 'teal' : 'blue'} />}
            title={<>{n.pinned && <Icon name="pin" size={14} className="notice-pin" />}{n.title}</>}
            meta={[t(`comms.kind.${n.kind}`), n.summary, n.publishedAt && relative(n.publishedAt), n.version > 1 && t('comms.versionN', { n: n.version })]
              .filter(Boolean).join(' · ')}
            trail={n.requiresAck
              ? (n.acknowledgedAt ? <Status tone="green">{t('comms.acknowledged')}</Status> : <Status tone="amber">{t('comms.toAcknowledge')}</Status>)
              : !n.readAt ? <Status tone="blue">{t('comms.new')}</Status> : undefined} />
        ))}
        {all.isPending && <Row title={t('common.loading')} />}
      </List>
    </Card>
  );
}

/** One notice to read. Opening it records the read receipt; policies can be acknowledged here. */
function NoticePanel({ id, onClose }: { id: string; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const notice = useQuery({ queryKey: ['notices', 'one', id], queryFn: () => api.notices.open(id) });
  const [fileError, setFileError] = useState<string | null>(null);
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['notices'] });
  };
  const ack = useMutation({ mutationFn: () => api.notices.acknowledge(id), onSuccess: refresh });
  const n = notice.data;
  const loadedId = n?.id;
  // Opening it recorded the read receipt: refresh the list and the menu badge.
  useEffect(() => {
    if (!loadedId) return;
    void queryClient.invalidateQueries({ queryKey: ['notices', 'mine'] });
    void queryClient.invalidateQueries({ queryKey: ['notices', 'counts'] });
  }, [loadedId, queryClient]);

  return (
    <Panel title={n?.title ?? t('common.loading')} onClose={onClose}>
      {notice.error && <p className="form-error" role="alert">{errorText(notice.error)}</p>}
      {n && (
        <article className="stack">
          <p className="hint">
            {[t(`comms.kind.${n.kind}`), n.publishedBy && t('comms.by', { name: n.publishedBy }),
              n.publishedAt && formatDate(n.publishedAt, { day: 'numeric', month: 'long', year: 'numeric' }),
              n.version > 1 && t('comms.versionN', { n: n.version })].filter(Boolean).join(' · ')}
          </p>
          {n.summary && <p className="notice-summary">{n.summary}</p>}
          <NoticeBody text={n.body} />
          {n.attachments.length > 0 && (
            <section className="stack">
              <h3 className="panel-subhead">{t('comms.attachments')}</h3>
              <ul className="notice-files">
                {n.attachments.map((f) => (
                  <li key={f.id}>
                    <button type="button" className="link-button" onClick={() => void openBlob(() => api.notices.attachment(n.id, f.id), setFileError)}>
                      <Icon name="file" size={15} /> {f.name}
                    </button>
                  </li>
                ))}
              </ul>
              {fileError && <p className="form-error" role="alert">{fileError}</p>}
            </section>
          )}
          {n.requiresAck && n.status === 'PUBLISHED' && (
            <div className="notice-ack">
              {n.acknowledgedAt ? (
                <Status tone="green">{t('comms.acknowledgedOn', { date: formatDate(n.acknowledgedAt, { day: 'numeric', month: 'short', year: 'numeric' }) })}</Status>
              ) : (
                <>
                  <p>{t('comms.ackPrompt')}</p>
                  {ack.error && <p className="form-error" role="alert">{errorText(ack.error)}</p>}
                  <button type="button" className="btn primary" onClick={() => ack.mutate()} disabled={ack.isPending}>{t('comms.acknowledge')}</button>
                </>
              )}
            </div>
          )}
        </article>
      )}
    </Panel>
  );
}

function MyLetters() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const letters = useQuery({ queryKey: ['letters', 'mine'], queryFn: () => api.letters.mine() });
  const [error, setError] = useState<string | null>(null);
  const open = (l: IssuedLetter) => void openBlob(() => api.letters.file(l.id), setError)
    .then(() => queryClient.invalidateQueries({ queryKey: ['letters', 'mine'] }));
  if (letters.data && letters.data.length === 0) {
    return <EmptyState icon="file" tone="purple" title={t('comms.letters.none')}>{t('comms.letters.noneHint')}</EmptyState>;
  }
  return (
    <Card>
      {(letters.error || error) && <p className="form-error" role="alert">{letters.error ? errorText(letters.error) : error}</p>}
      <List>
        {letters.data?.map((l) => (
          <Row key={l.id} lead={<Glyph icon="file" tone="purple" />} title={l.subject}
            meta={[t(`comms.letterKind.${l.kind}`), formatDate(l.issuedAt, { day: 'numeric', month: 'short', year: 'numeric' }), l.issuedBy].filter(Boolean).join(' · ')}
            trail={<>{!l.firstViewedAt && <Status tone="blue">{t('comms.new')}</Status>}<button type="button" className="btn small" onClick={() => open(l)}>{t('files.open')}</button></>} />
        ))}
        {letters.isPending && <Row title={t('common.loading')} />}
      </List>
    </Card>
  );
}
