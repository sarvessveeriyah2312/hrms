import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { FileMeta, LetterKind, LetterTemplate, LetterTemplateInput, Letterhead, LetterheadInput } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { formatDate } from '../../lib/dates';
import { Card, Status, Toggle } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Table } from '../../components/app/Table';
import { FileField } from '../../components/app/files';
import { openBlob } from '../payroll/payrollText';

const KINDS: LetterKind[] = ['OFFER', 'CONFIRMATION', 'INCREMENT', 'PROMOTION', 'EXPERIENCE', 'WARNING', 'OTHER'];

/** Issue letters on the company letterhead, see those issued, and edit templates and the letterhead (comms:letters). */
export function LettersAdmin() {
  const { t } = useTranslation();
  const [panel, setPanel] = useState<'letterhead' | LetterTemplate | 'newTemplate' | null>(null);
  const templates = useQuery({ queryKey: ['letters', 'templates'], queryFn: () => api.letters.templates() });

  return (
    <div className="stack">
      <div className="grid grid--main">
        <IssueLetter templates={templates.data ?? []} />
        <Card title={t('comms.letters.templates')} count={templates.data?.length}
          action={<button type="button" className="btn small" onClick={() => setPanel('newTemplate')}>{t('comms.letters.newTemplate')}</button>}>
          <ul className="comms-templates">
            {templates.data?.map((tpl) => (
              <li key={tpl.id}>
                <button type="button" className="link-button" onClick={() => setPanel(tpl)}>{tpl.name}</button>
                <small>{t(`comms.letterKind.${tpl.kind}`)}{!tpl.active && ` · ${t('common.inactive')}`}</small>
              </li>
            ))}
          </ul>
          <div className="comms-letterhead-link">
            <button type="button" className="btn small" onClick={() => setPanel('letterhead')}>{t('comms.letters.editLetterhead')}</button>
          </div>
        </Card>
      </div>
      <IssuedLetters />
      {panel === 'letterhead' && <LetterheadPanel onClose={() => setPanel(null)} />}
      {panel && panel !== 'letterhead' && (
        <TemplatePanel template={panel === 'newTemplate' ? null : panel} onClose={() => setPanel(null)} />
      )}
    </div>
  );
}

function IssueLetter({ templates }: { templates: LetterTemplate[] }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [q, setQ] = useState('');
  const people = useQuery({ queryKey: ['employees', q], queryFn: () => api.employees(q) });
  const [employeeId, setEmployeeId] = useState('');
  const [templateId, setTemplateId] = useState('');
  const [values, setValues] = useState<Record<string, string>>({});
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [issued, setIssued] = useState<string | null>(null);
  const active = templates.filter((x) => x.active);
  const template = active.find((x) => x.id === templateId);
  const input = () => ({ templateId, employeeId, values });
  const issue = useMutation({
    mutationFn: () => api.letters.issue(input()),
    onSuccess: async (l) => {
      setIssued(t('comms.letters.issued', { name: l.employeeName }));
      setValues({});
      await queryClient.invalidateQueries({ queryKey: ['letters'] });
    },
  });
  const ready = !!template && !!employeeId && template.fields.every((f) => (values[f] ?? '').trim());

  return (
    <Card title={t('comms.letters.issue')} padded>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); issue.mutate(); }}>
        {issue.error && <p className="form-error" role="alert">{errorText(issue.error)}</p>}
        {previewError && <p className="form-error" role="alert">{previewError}</p>}
        {issued && <p className="payroll-done" role="status">{issued}</p>}
        <label className="field">
          {t('comms.letters.template')}
          <select value={templateId} onChange={(e) => { setTemplateId(e.target.value); setIssued(null); }} required>
            <option value="">{t('profile.choose')}</option>
            {active.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
          </select>
        </label>
        <div className="field-row">
          <label className="field">
            {t('comms.letters.findPerson')}
            <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('people.search')} />
          </label>
          <label className="field">
            {t('comms.letters.person')}
            <select value={employeeId} onChange={(e) => { setEmployeeId(e.target.value); setIssued(null); }} required>
              <option value="">{t('profile.choose')}</option>
              {people.data?.employees.map((p) => <option key={p.id} value={p.id}>{p.fullName} ({p.employeeNo})</option>)}
            </select>
          </label>
        </div>
        {template && template.fields.length > 0 && (
          <fieldset>
            <legend>{t('comms.letters.fillIn')}</legend>
            <div className="field-row">
              {template.fields.map((f) => (
                <label key={f} className="field">
                  {fieldName(f)}
                  <input value={values[f] ?? ''} onChange={(e) => setValues((v) => ({ ...v, [f]: e.target.value }))} maxLength={500} required />
                </label>
              ))}
            </div>
          </fieldset>
        )}
        {template && <p className="hint">{t('comms.letters.autoHint')}</p>}
        <div className="panel-actions">
          <button type="button" className="btn" disabled={!ready} onClick={() => void openBlob(() => api.letters.preview(input()), setPreviewError)}>
            {t('comms.letters.preview')}
          </button>
          <button type="submit" className="btn primary" disabled={!ready || issue.isPending}>{t('comms.letters.issueButton')}</button>
        </div>
      </form>
    </Card>
  );
}

/** "effectiveDate" → "Effective date". */
function fieldName(f: string) {
  const s = f.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function IssuedLetters() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const letters = useQuery({ queryKey: ['letters', 'all'], queryFn: () => api.letters.all() });
  const [error, setError] = useState<string | null>(null);
  const withdraw = useMutation({
    mutationFn: (id: string) => api.letters.withdraw(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['letters'] }),
  });
  const day = (iso: string) => formatDate(iso, { day: 'numeric', month: 'short', year: 'numeric' });
  return (
    <Card title={t('comms.letters.issuedTitle')} count={letters.data?.length}>
      {(letters.error || withdraw.error || error) && <p className="form-error" role="alert">{error ?? errorText(letters.error ?? withdraw.error)}</p>}
      <Table rows={letters.data ?? []} rowKey={(l) => l.id} label={t('comms.letters.issuedTitle')} loading={letters.isPending}
        empty={t('comms.letters.noneIssued')}
        columns={[
          { key: 'person', header: t('payroll.col.person'), rowHeader: true, cell: (l) => <>{l.employeeName}<small>{l.employeeNo}</small></> },
          { key: 'subject', header: t('comms.letters.subject'), cell: (l) => <>{l.subject}<small>{t(`comms.letterKind.${l.kind}`)}</small></> },
          { key: 'issued', header: t('comms.letters.issuedOn'), cell: (l) => <>{day(l.issuedAt)}{l.issuedBy && <small>{l.issuedBy}</small>}</> },
          { key: 'opened', header: t('payroll.col.read'),
            cell: (l) => l.withdrawnAt ? <Status tone="grey">{t('comms.letters.withdrawn')}</Status>
              : l.firstViewedAt ? <Status tone="green">{t('payroll.read.yes', { when: formatDate(l.firstViewedAt, { day: 'numeric', month: 'short' }) })}</Status>
                : <Status tone="amber">{t('payroll.read.no')}</Status> },
          { key: 'actions', header: <span className="sr-only">{t('payroll.col.actions')}</span>, align: 'end',
            cell: (l) => (
              <span className="payroll-row-actions">
                <button type="button" className="btn small" onClick={() => void openBlob(() => api.letters.file(l.id), setError)}>{t('files.open')}</button>
                {!l.withdrawnAt && <button type="button" className="btn small reject" disabled={withdraw.isPending} onClick={() => withdraw.mutate(l.id)}>{t('comms.letters.withdraw')}</button>}
              </span>
            ) },
        ]} />
    </Card>
  );
}

function TemplatePanel({ template, onClose }: { template: LetterTemplate | null; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const auto = useQuery({ queryKey: ['letters', 'autoFields'], queryFn: () => api.letters.autoFields(), staleTime: Infinity });
  const [form, setForm] = useState<LetterTemplateInput>(() => ({
    kind: template?.kind ?? 'OTHER', name: template?.name ?? '', subject: template?.subject ?? '', body: template?.body ?? '', active: template?.active ?? true,
  }));
  const [confirm, setConfirm] = useState(false);
  const set = <K extends keyof LetterTemplateInput>(k: K, v: LetterTemplateInput[K]) => setForm((f) => ({ ...f, [k]: v }));
  const done = async () => { await queryClient.invalidateQueries({ queryKey: ['letters', 'templates'] }); onClose(); };
  const save = useMutation({ mutationFn: () => (template ? api.letters.updateTemplate(template.id, form) : api.letters.createTemplate(form)), onSuccess: done });
  const remove = useMutation({ mutationFn: () => api.letters.deleteTemplate(template!.id), onSuccess: done });

  return (
    <Panel title={template ? t('comms.letters.editTemplate', { name: template.name }) : t('comms.letters.newTemplate')} onClose={onClose}>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
        {(save.error || remove.error) && <p className="form-error" role="alert">{errorText(save.error ?? remove.error)}</p>}
        <div className="field-row">
          <label className="field">
            {t('comms.letters.name')}
            <input value={form.name} onChange={(e) => set('name', e.target.value)} maxLength={100} required />
          </label>
          <label className="field">
            {t('comms.letters.kind')}
            <select value={form.kind} onChange={(e) => set('kind', e.target.value as LetterKind)}>
              {KINDS.map((k) => <option key={k} value={k}>{t(`comms.letterKind.${k}`)}</option>)}
            </select>
          </label>
        </div>
        <label className="field">
          {t('comms.letters.subject')}
          <input value={form.subject} onChange={(e) => set('subject', e.target.value)} maxLength={200} required />
        </label>
        <label className="field">
          {t('comms.letters.body')}
          <textarea className="notice-editor__body" value={form.body} onChange={(e) => set('body', e.target.value)} maxLength={20000} required />
        </label>
        <div className="hint comms-placeholders">
          <p>{t('comms.letters.placeholderHint', { example: '{{salary}}' })}</p>
          <p>{(auto.data ?? []).map((f) => <code key={f}>{`{{${f}}}`}</code>)}</p>
        </div>
        <Toggle checked={form.active} onChange={(v) => set('active', v)} label={t('comms.letters.active')} />
        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={save.isPending}>{t('common.save')}</button>
          {template && (confirm
            ? <button type="button" className="btn reject" onClick={() => remove.mutate()}>{t('comms.manage.confirmDelete')}</button>
            : <button type="button" className="btn reject" onClick={() => setConfirm(true)}>{t('common.delete')}</button>)}
        </div>
      </form>
    </Panel>
  );
}

function LetterheadPanel({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const head = useQuery({ queryKey: ['letters', 'letterhead'], queryFn: () => api.letters.letterhead() });
  return (
    <Panel title={t('comms.letters.letterhead')} onClose={onClose}>
      {head.error && <p className="form-error" role="alert">{errorText(head.error)}</p>}
      {head.data && <LetterheadForm head={head.data} onSaved={async () => { await queryClient.invalidateQueries({ queryKey: ['letters', 'letterhead'] }); onClose(); }} />}
    </Panel>
  );
}

function LetterheadForm({ head, onSaved }: { head: Letterhead; onSaved: () => void }) {
  const { t } = useTranslation();
  const [form, setForm] = useState<LetterheadInput>({ ...head, logoFileId: head.logo?.id ?? null });
  const [logo, setLogo] = useState<FileMeta | null>(head.logo);
  const set = (k: keyof LetterheadInput, v: string) => setForm((f) => ({ ...f, [k]: v || null }));
  const save = useMutation({ mutationFn: () => api.letters.saveLetterhead({ ...form, logoFileId: logo?.id ?? null }), onSuccess: onSaved });
  const text = (k: 'companyName' | 'registrationNo' | 'phone' | 'email' | 'website' | 'signatoryName' | 'signatoryTitle' | 'footer', optional = true) => (
    <label className="field">
      <span className="field-label">{t(`comms.letterhead.${k}`)}{optional && <span className="field-optional">{t('common.optional')}</span>}</span>
      <input value={form[k] ?? ''} onChange={(e) => set(k, e.target.value)} />
    </label>
  );
  return (
    <form className="stack" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      {save.error && <p className="form-error" role="alert">{errorText(save.error)}</p>}
      <p className="hint">{t('comms.letterhead.intro')}</p>
      <FileField label={t('comms.letterhead.logo')} current={logo} onChange={setLogo} />
      {text('companyName', false)}
      {text('registrationNo')}
      <label className="field">
        <span className="field-label">{t('comms.letterhead.address')}<span className="field-optional">{t('common.optional')}</span></span>
        <textarea value={form.address ?? ''} onChange={(e) => set('address', e.target.value)} rows={3} />
      </label>
      <div className="field-row">{text('phone')}{text('email')}{text('website')}</div>
      <fieldset>
        <legend>{t('comms.letterhead.signedBy')}</legend>
        <div className="field-row">{text('signatoryName')}{text('signatoryTitle')}</div>
      </fieldset>
      {text('footer')}
      <div className="panel-actions">
        <button type="submit" className="btn primary" disabled={save.isPending}>{t('common.save')}</button>
      </div>
    </form>
  );
}
