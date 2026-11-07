import { useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { EmployeeProfile, JobDetails, ProfileChange, ProfileSectionSpec, SectionData } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { formatDate, isoDate, relative } from '../../lib/dates';
import { Card, EmptyState, List, PageHeader, Row, Status, Tabs, type StatusTone } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Glyph } from '../../components/Glyph';
import { Timeline } from '../approvals/Timeline';
import { SectionForm } from './SectionForm';
import { ChangeCompare } from './ChangeCompare';
import { useSectionSpecs } from './useSectionSpecs';
import { ProfilePhoto } from './ProfilePhoto';
import { DocumentsTab } from './DocumentsTab';
import { AssetsTab } from './AssetsTab';
import { fieldLabel, formatValue, isEmpty, itemSummary, items, sectionHint, sectionLabel, tx } from './sectionText';
import './profile.css';

const EMPLOYMENT_TYPES = ['PERMANENT', 'CONTRACT', 'PART_TIME', 'INTERN', 'TEMPORARY'] as const;

const day = (iso: string | null | undefined) => (iso ? formatDate(iso, { day: 'numeric', month: 'short', year: 'numeric' }) : null);

/** Active, leaving on a date, or left. */
function employmentStatus(p: EmployeeProfile): { tone: StatusTone; label: string } {
  const today = isoDate(new Date());
  if (p.job.lastDay && p.job.lastDay < today) return { tone: 'grey', label: tx('profile.status.left', undefined, { date: day(p.job.lastDay) }) };
  if (p.job.lastDay) return { tone: 'amber', label: tx('profile.status.leaving', undefined, { date: day(p.job.lastDay) }) };
  if (p.job.resignedOn) return { tone: 'amber', label: tx('profile.status.resigned') };
  return p.status === 'ACTIVE' ? { tone: 'green', label: tx('common.active') } : { tone: 'grey', label: tx('common.inactive') };
}

type Editing =
  | { kind: 'section'; spec: ProfileSectionSpec; mode: 'save' | 'request' }
  | { kind: 'resubmit'; spec: ProfileSectionSpec; change: ProfileChange; data: SectionData }
  | { kind: 'job' };

/**
 * An employee's profile: /app/profile for your own, /app/people/:id for someone else's. What shows and what can be
 * changed follows the API's access answer: HR edits directly; you ask HR for changes to your own; managers see job,
 * contact details, emergency contacts and education.
 */
export function ProfilePage() {
  const { t } = useTranslation();
  const { id: routeId } = useParams();
  const id = routeId ?? 'me';
  const [params, setParams] = useSearchParams();
  const profile = useQuery({ queryKey: ['profile', id], queryFn: () => api.profiles.get(id) });
  const specs = useSectionSpecs();
  const [editing, setEditing] = useState<Editing | null>(null);
  const [viewingChange, setViewingChange] = useState<string | null>(null);

  const p = profile.data;
  const name = p?.job.fullName ?? '';
  const tabs = p ? [
    { key: 'overview', label: t('profile.tabs.overview') },
    ...(p.access.seePrivate ? [{ key: 'documents', label: t('profile.tabs.documents') }, { key: 'assets', label: t('profile.tabs.assets') }] : []),
    ...(p.access.self ? [{ key: 'changes', label: t('profile.tabs.changes'), count: p.openChanges.length || undefined }] : []),
  ] : [];
  const tab = tabs.some((x) => x.key === params.get('tab')) ? params.get('tab')! : 'overview';
  const setTab = (key: string) => setParams(key === 'overview' ? {} : { tab: key }, { replace: true });

  if (profile.isError) {
    return (
      <>
        <PageHeader title={routeId ? t('nav.people') : t('nav.profile')} />
        <p className="form-error" role="alert">{errorText(profile.error)}</p>
      </>
    );
  }
  if (!p) return <PageHeader title={routeId ? t('nav.people') : t('nav.profile')} subtitle={t('common.loading')} />;

  const status = employmentStatus(p);
  const openFor = (section: string) => p.openChanges.find((c) => c.section === section);

  return (
    <>
      <PageHeader
        docTitle={name}
        eyebrow={[p.employeeNo, p.entity].join(' · ')}
        title={<span className="profile-title"><PhotoControl profile={p} id={id} name={name} />{name}</span>}
        subtitle={(
          <>
            <Status tone={status.tone}>{status.label}</Status>
            {[p.job.jobTitle, p.department, p.manager && t('dashboard.reportsTo', { name: p.manager })].filter(Boolean).join(' · ')}
          </>
        )}
      />

      <Tabs tabs={tabs} value={tab} onChange={setTab} label={t('profile.tabsLabel')} titled>
        {tab === 'overview' && (
          <div className="grid grid--two">
            <JobCard profile={p} onEdit={p.access.manage ? () => setEditing({ kind: 'job' }) : undefined} />
            {(specs.data ?? []).filter((s) => p.sections[s.key] !== undefined).map((s) => (
              <SectionCard key={s.key} spec={s} data={p.sections[s.key]} profile={p} open={openFor(s.key)}
                onEdit={() => setEditing({ kind: 'section', spec: s, mode: p.access.manage ? 'save' : 'request' })}
                onViewChange={(c) => setViewingChange(c.id)} />
            ))}
            {p.hiddenSections.length > 0 && (
              <Card padded>
                <p className="hint">{t('profile.privateNote', { sections: p.hiddenSections.map(sectionLabel).join(', ') })}</p>
              </Card>
            )}
          </div>
        )}
        {tab === 'documents' && <DocumentsTab id={id} canAdd={p.access.self || p.access.manage} />}
        {tab === 'assets' && <AssetsTab id={id} canManage={p.access.manage} />}
        {tab === 'changes' && <ChangesTab onView={(c) => setViewingChange(c.id)} />}
      </Tabs>

      {editing?.kind === 'job' && <JobPanel id={id} job={p.job} onClose={() => setEditing(null)} />}
      {editing?.kind === 'section' && (
        <SectionPanel id={id} spec={editing.spec} mode={editing.mode} initial={p.sections[editing.spec.key]} onClose={() => setEditing(null)} />
      )}
      {editing?.kind === 'resubmit' && (
        <SectionPanel id={id} spec={editing.spec} mode="request" initial={editing.data} change={editing.change} onClose={() => setEditing(null)} />
      )}
      {viewingChange && (
        <ChangePanel changeId={viewingChange} onClose={() => setViewingChange(null)}
          onEdit={(change, data) => {
            const spec = specs.data?.find((s) => s.key === change.section);
            setViewingChange(null);
            if (spec) setEditing({ kind: 'resubmit', spec, change, data });
          }} />
      )}
    </>
  );
}

// ---------- photo ----------

function PhotoControl({ profile, id, name }: { profile: EmployeeProfile; id: string; name: string }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);
  const canChange = profile.access.self || profile.access.manage;
  const change = useMutation({
    mutationFn: async (file: File) => {
      if (file.size > 5 * 1024 * 1024) throw new Error(t('files.tooBig'));
      const meta = await api.files.upload(file);
      await api.profiles.setPhoto(id, meta.id);
    },
    onSuccess: async () => {
      setError(null);
      await queryClient.invalidateQueries({ queryKey: ['profile'] });
    },
    onError: (e) => setError(e instanceof Error && !('problem' in e) ? e.message : errorText(e)),
  });
  const remove = useMutation({
    mutationFn: () => api.profiles.removePhoto(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['profile'] }),
  });
  return (
    <span className="profile-photo-wrap">
      <ProfilePhoto id={id} name={name} hasPhoto={profile.hasPhoto} size={56} />
      {canChange && (
        <span className="profile-photo-actions">
          <button type="button" className="link-button" onClick={() => input.current?.click()} disabled={change.isPending}>
            {change.isPending ? t('files.uploading') : profile.hasPhoto ? t('profile.changePhoto') : t('profile.addPhoto')}
          </button>
          {profile.hasPhoto && (
            <button type="button" className="link-button" onClick={() => remove.mutate()} disabled={remove.isPending}>{t('common.remove')}</button>
          )}
          <input ref={input} type="file" accept="image/jpeg,image/png" hidden aria-label={t('profile.addPhoto')}
            onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) change.mutate(f); }} />
          {error && <span className="error" role="alert">{error}</span>}
        </span>
      )}
    </span>
  );
}

// ---------- job ----------

function JobCard({ profile: p, onEdit }: { profile: EmployeeProfile; onEdit?: () => void }) {
  const { t } = useTranslation();
  const j = p.job;
  const rows: [string, string | null][] = [
    [t('profile.job.jobTitle'), j.jobTitle],
    [t('profile.job.employmentType'), j.employmentType ? t(`profile.employment.${j.employmentType}`) : null],
    [t('profile.job.department'), p.department],
    [t('common.manager'), p.manager],
    [t('profile.job.entity'), p.entity],
    [t('profile.job.joinDate'), day(j.joinDate)],
    [t('profile.job.confirmationDue'), day(j.confirmationDue)],
    [t('profile.job.confirmedOn'), day(j.confirmedOn)],
    [t('profile.job.resignedOn'), day(j.resignedOn)],
    [t('profile.job.lastDay'), day(j.lastDay)],
    [t('profile.job.email'), p.email],
  ];
  return (
    <Card title={t('profile.job.title')} padded action={onEdit && <button type="button" className="btn small" onClick={onEdit}>{t('common.edit')}</button>}>
      <dl className="facts">
        {rows.filter(([, v]) => v).map(([k, v]) => <FactRow key={k} label={k} value={v!} />)}
      </dl>
    </Card>
  );
}

function FactRow({ label, value }: { label: string; value: string }) {
  return <><dt>{label}</dt><dd>{value}</dd></>;
}

function JobPanel({ id, job, onClose }: { id: string; job: JobDetails; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [form, setForm] = useState<JobDetails>(job);
  const set = (k: keyof JobDetails, v: string) => setForm((f) => ({ ...f, [k]: v === '' ? null : v }));
  const save = useMutation({
    mutationFn: () => api.profiles.saveJob(id, { ...form, fullName: form.fullName ?? '' }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['profile'] });
      await queryClient.invalidateQueries({ queryKey: ['employees'] });
      onClose();
    },
  });
  const date = (k: 'joinDate' | 'confirmationDue' | 'confirmedOn' | 'resignedOn' | 'lastDay', optional = true) => (
    <label className="field">
      <span className="field-label">{t(`profile.job.${k}`)}{optional && <span className="field-optional">{t('common.optional')}</span>}</span>
      <input type="date" value={form[k] ?? ''} onChange={(e) => set(k, e.target.value)} required={!optional} />
    </label>
  );
  return (
    <Panel title={t('profile.job.editTitle')} onClose={onClose}>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
        {save.error && <p className="form-error" role="alert">{errorText(save.error)}</p>}
        <label className="field">
          {t('profile.job.fullName')}
          <input value={form.fullName ?? ''} onChange={(e) => setForm((f) => ({ ...f, fullName: e.target.value }))} maxLength={120} required />
        </label>
        <div className="field-row">
          <label className="field">
            <span className="field-label">{t('profile.job.jobTitle')}<span className="field-optional">{t('common.optional')}</span></span>
            <input value={form.jobTitle ?? ''} onChange={(e) => set('jobTitle', e.target.value)} maxLength={120} />
          </label>
          <label className="field">
            <span className="field-label">{t('profile.job.employmentType')}<span className="field-optional">{t('common.optional')}</span></span>
            <select value={form.employmentType ?? ''} onChange={(e) => set('employmentType', e.target.value)}>
              <option value="">{t('profile.choose')}</option>
              {EMPLOYMENT_TYPES.map((x) => <option key={x} value={x}>{t(`profile.employment.${x}`)}</option>)}
            </select>
          </label>
        </div>
        <div className="field-row">
          {date('joinDate', false)}
          {date('confirmationDue')}
          {date('confirmedOn')}
        </div>
        <fieldset>
          <legend>{t('profile.job.leaving')}</legend>
          <div className="field-row">
            {date('resignedOn')}
            {date('lastDay')}
          </div>
          <p className="hint">{t('profile.job.leavingHint')}</p>
        </fieldset>
        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={save.isPending}>{t('common.save')}</button>
          <button type="button" className="btn" onClick={onClose} disabled={save.isPending}>{t('common.cancel')}</button>
        </div>
      </form>
    </Panel>
  );
}

// ---------- sections ----------

function SectionCard({ spec, data, profile, open, onEdit, onViewChange }: {
  spec: ProfileSectionSpec; data: SectionData; profile: EmployeeProfile; open?: ProfileChange;
  onEdit: () => void; onViewChange: (c: ProfileChange) => void;
}) {
  const { t } = useTranslation();
  const canEdit = profile.access.manage || profile.access.self;
  const action = (
    <span className="profile-card-actions">
      {open && (
        <button type="button" className="link-button" onClick={() => onViewChange(open)}>
          <Status tone={open.status === 'RETURNED' ? 'red' : 'amber'}>{open.status === 'RETURNED' ? t('profile.sentBack') : t('profile.waitingForHr')}</Status>
        </button>
      )}
      {canEdit && !open && (
        <button type="button" className="btn small" onClick={onEdit}>{profile.access.manage ? t('common.edit') : t('profile.askChange')}</button>
      )}
    </span>
  );
  const empty = isEmpty(spec, data);
  return (
    <Card title={sectionLabel(spec.key)} padded action={action}>
      {sectionHint(spec.key) && <p className="hint">{sectionHint(spec.key)}</p>}
      {empty ? (
        <p className="hint">{t('profile.notFilled')}</p>
      ) : spec.list ? (
        <ul className="profile-items">
          {items(data).map((i, n) => {
            const s = itemSummary(spec, i);
            return <li key={n}><strong>{s.title}</strong>{s.meta && <span className="hint">{s.meta}</span>}</li>;
          })}
        </ul>
      ) : (
        <dl className="facts">
          {spec.fields.filter((f) => data[f.key] != null && data[f.key] !== '').map((f) => (
            <FactRow key={f.key} label={fieldLabel(f.key)} value={formatValue(f, data[f.key])} />
          ))}
        </dl>
      )}
    </Card>
  );
}

function SectionPanel({ id, spec, mode, initial, change, onClose }: {
  id: string; spec: ProfileSectionSpec; mode: 'save' | 'request'; initial: SectionData | undefined; change?: ProfileChange; onClose: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const save = useMutation({
    mutationFn: async ({ data, note }: { data: SectionData; note?: string }): Promise<void> => {
      if (mode === 'save') await api.profiles.saveSection(id, spec.key, data);
      else if (change) await api.profiles.changes.resubmit(change.id, data, note);
      else await api.profiles.changes.ask(spec.key, data, note);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['profile'] });
      onClose();
    },
  });
  const title = mode === 'save' ? t('profile.editTitle', { section: sectionLabel(spec.key) }) : t('profile.askTitle', { section: sectionLabel(spec.key) });
  return (
    <Panel title={title} onClose={onClose}>
      <SectionForm spec={spec} initial={initial} mode={mode} initialNote={change?.note} busy={save.isPending}
        error={save.error ? errorText(save.error) : null} onSubmit={(data, note) => save.mutate({ data, note })} onCancel={onClose} />
    </Panel>
  );
}

// ---------- change requests ----------

const CHANGE_TONE: Record<string, StatusTone> = { PENDING: 'amber', APPROVED: 'green', REJECTED: 'red', RETURNED: 'red', CANCELLED: 'grey' };

function ChangesTab({ onView }: { onView: (c: ProfileChange) => void }) {
  const { t } = useTranslation();
  const mine = useQuery({ queryKey: ['profile', 'changes'], queryFn: () => api.profiles.changes.mine() });
  if (mine.data && mine.data.length === 0) {
    return <EmptyState icon="users" tone="green" title={t('profile.noChanges')}>{t('profile.noChangesHint')}</EmptyState>;
  }
  return (
    <Card title={t('profile.tabs.changes')} count={mine.data?.length}>
      {mine.isError && <p className="form-error" role="alert">{errorText(mine.error)}</p>}
      <List>
        {mine.data?.map((c) => (
          <Row key={c.id} lead={<Glyph icon="users" tone="green" />} title={sectionLabel(c.section)} onClick={() => onView(c)}
            meta={[relative(c.createdAt), changedText(c), c.pendingWith && t('dashboard.with', { name: c.pendingWith.approver })].filter(Boolean).join(' · ')}
            trail={<Status tone={CHANGE_TONE[c.status]}>{t(`status.${c.status}`)}</Status>} />
        ))}
        {mine.isPending && <Row title={t('common.loading')} />}
      </List>
    </Card>
  );
}

function changedText(c: ProfileChange) {
  if (c.changedFields.includes('items')) return tx('profile.listChanged');
  return c.changedFields.map(fieldLabel).join(', ');
}

function ChangePanel({ changeId, onClose, onEdit }: { changeId: string; onClose: () => void; onEdit: (c: ProfileChange, data: SectionData) => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const detail = useQuery({ queryKey: ['profile', 'change', changeId], queryFn: () => api.profiles.changes.detail(changeId) });
  const cancel = useMutation({
    mutationFn: () => api.profiles.changes.cancel(changeId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['profile'] });
      onClose();
    },
  });
  const c = detail.data?.change;
  return (
    <Panel title={c ? t('profile.changeTitle', { section: sectionLabel(c.section) }) : t('common.loading')} onClose={onClose}>
      {detail.error && <p className="form-error" role="alert">{errorText(detail.error)}</p>}
      {detail.data && c && (
        <>
          <p><Status tone={CHANGE_TONE[c.status]}>{t(`status.${c.status}`)}</Status></p>
          <ChangeCompare detail={detail.data} />
          {detail.data.timeline.length > 0 && (
            <section className="stack">
              <h3 className="panel-subhead">{t('req.chain')}</h3>
              <Timeline steps={detail.data.timeline} />
            </section>
          )}
          {cancel.error && <p className="form-error" role="alert">{errorText(cancel.error)}</p>}
          {(c.canEdit || c.canCancel) && (
            <div className="panel-actions">
              {c.canEdit && <button type="button" className="btn primary" onClick={() => onEdit(c, detail.data!.proposed)}>{t('profile.editResend')}</button>}
              {c.canCancel && <button type="button" className="btn reject" onClick={() => cancel.mutate()} disabled={cancel.isPending}>{t('profile.withdraw')}</button>}
            </div>
          )}
        </>
      )}
    </Panel>
  );
}

