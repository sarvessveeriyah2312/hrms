import { useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { FlowSubject } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText, hasModule, useAccess } from '../../lib/access';
import { useOpenParam } from '../../lib/openParam';
import { Glyph } from '../../components/Glyph';
import { Icon } from '../../components/Icon';
import { Card, EmptyState, PageHeader, Tabs } from '../../components/app/kit';
import { FlowEditor, FlowList } from './FlowBuilder';
import './workflows.css';

const TABS = ['flows', 'try'] as const;
type Tab = (typeof TABS)[number];

/**
 * Approval workflow settings: build who approves which leave requests or claims, in what order, and try it out.
 * ?for=claims switches to claim workflows and ?for=corrections to attendance corrections (only when the company has
 * that module).
 */
export function WorkflowsPage() {
  const { t } = useTranslation();
  const { user } = useAccess();
  const [params, setParams] = useSearchParams();
  const hasClaims = hasModule(user, 'claims');
  const hasLeave = hasModule(user, 'leave');
  const hasAttendance = hasModule(user, 'attendance');
  const subjects = ([hasLeave && 'LEAVE', hasClaims && 'CLAIM', hasAttendance && 'CORRECTION'] as const)
    .filter((s): s is FlowSubject => !!s);
  const wanted: FlowSubject = params.get('for') === 'claims' ? 'CLAIM' : params.get('for') === 'corrections' ? 'CORRECTION' : 'LEAVE';
  const subject: FlowSubject = subjects.includes(wanted) ? wanted : subjects[0] ?? 'LEAVE';
  const correction = subject === 'CORRECTION';
  // Corrections have nothing to try out (no types or amounts), so they only have the workflows tab.
  const requested: Tab = (TABS as readonly string[]).includes(params.get('tab') ?? '') ? (params.get('tab') as Tab) : 'flows';
  const tab: Tab = correction ? 'flows' : requested;
  const go = (next: { subject?: FlowSubject; tab?: Tab }) => {
    const s = next.subject ?? subject;
    const tb = next.tab ?? tab;
    const forParam = s === 'CLAIM' ? { for: 'claims' } : s === 'CORRECTION' ? { for: 'corrections' } : {};
    setParams({ ...forParam, ...(tb === 'try' && s !== 'CORRECTION' ? { tab: 'try' } : {}) }, { replace: true });
  };
  const flows = useQuery({ queryKey: ['workflow', 'flows', subject], queryFn: () => api.workflow.flows(subject) });
  // Selection per subject, so switching back keeps your place.
  const [selectedBy, setSelectedBy] = useState<Partial<Record<FlowSubject, string | 'new' | null>>>({});
  // Opened from search: ?open=<workflow id>
  const [openId, clearOpen] = useOpenParam();
  const selected = openId ?? selectedBy[subject] ?? null;
  const setSelected = (id: string | 'new' | null) => {
    setSelectedBy((s) => ({ ...s, [subject]: id }));
    clearOpen();
  };
  const current = selected === 'new' ? null : flows.data?.find((f) => f.id === selected) ?? flows.data?.[0] ?? null;
  const claim = subject === 'CLAIM';

  return (
    <>
      <PageHeader
        title={t('nav.workflows')}
        subtitle={tab === 'flows' ? (claim ? t('wf.subtitleClaim') : correction ? t('wf.subtitleCorrection') : t('wf.subtitle')) : (claim ? t('wf.trySubtitleClaim') : t('wf.trySubtitle'))}
        actions={tab === 'flows' && <button type="button" className="btn primary" onClick={() => setSelected('new')}><Icon name="plus" size={16} />{t('wf.new')}</button>}
      />

      {subjects.length > 1 && (
        <div className="wf-subject">
          <span className="wf-label" id="wf-subject-label">{t('wf.forLabel')}</span>
          <div className="segmented" role="radiogroup" aria-labelledby="wf-subject-label">
            {subjects.map((s) => (
              <label key={s} className={subject === s ? 'is-on' : undefined}>
                <input type="radio" name="wf-subject" checked={subject === s} onChange={() => go({ subject: s })} />
                {t(`wf.subjects.${s}`)}
              </label>
            ))}
          </div>
        </div>
      )}
      {flows.isError && <p className="form-error" role="alert">{errorText(flows.error)}</p>}

      <Tabs titled
        label={t('nav.workflows')}
        value={tab}
        onChange={(key) => go({ tab: key as Tab })}
        tabs={[
          { key: 'flows', label: t('wf.tabFlows'), icon: 'switch', count: flows.data?.length },
          ...(correction ? [] : [{ key: 'try', label: t('wf.tabTry'), icon: 'search' as const }]),
        ]}
      >
        {tab === 'flows' && (
          <div className="wf-layout">
            <FlowList subject={subject} flows={flows.data ?? []} selectedId={selected === 'new' ? 'new' : current?.id ?? null} onSelect={setSelected} onNew={() => setSelected('new')} />
            {selected === 'new' || current ? (
              <FlowEditor
                key={`${subject}-${selected === 'new' ? 'new' : current!.id}`}
                subject={subject}
                flow={selected === 'new' ? null : current}
                onSaved={(id) => setSelected(id)}
                onDeleted={() => setSelected(null)}
              />
            ) : (
              <Card>
                <EmptyState icon="switch" tone="blue" title={t('wf.noneTitle')}
                  action={<button type="button" className="btn primary" onClick={() => setSelected('new')}>{t('wf.buildFirst')}</button>}>
                  {claim ? t('wf.noneBodyClaim') : correction ? t('wf.noneBodyCorrection') : t('wf.noneBody')}
                </EmptyState>
              </Card>
            )}
          </div>
        )}
        {tab === 'try' && (claim ? <TryClaim key="claim" /> : <TryLeave key="leave" />)}
      </Tabs>
    </>
  );
}

type Preview = {
  workflow: string;
  fromType: boolean;
  steps: { position: number; approver: string; skipped: boolean; note: string }[];
};

function TryLeave() {
  const { t } = useTranslation();
  const types = useQuery({ queryKey: ['leave', 'types'], queryFn: api.leave.types });
  const [employeeId, setEmployeeId] = useState('');
  const [typeId, setTypeId] = useState('');
  const [days, setDays] = useState(1);
  const type = typeId || types.data?.[0]?.id || '';
  const preview = useQuery({
    queryKey: ['workflow', 'preview', 'LEAVE', employeeId, type, days],
    queryFn: async (): Promise<Preview> => {
      const p = await api.workflow.preview(employeeId, type, days);
      return { workflow: p.workflow, fromType: p.fromLeaveType, steps: p.steps };
    },
    enabled: !!employeeId && !!type && days > 0,
  });

  return (
    <TryLayout employeeId={employeeId} setEmployeeId={setEmployeeId} preview={preview} fromTypeText={t('wf.fromType')}>
      <label className="field">
        {t('leave.form.type')}
        <select value={type} onChange={(e) => setTypeId(e.target.value)}>
          {types.data?.map((lt) => <option key={lt.id} value={lt.id}>{lt.name}</option>)}
        </select>
      </label>
      <label className="field">
        {t('req.workingDays')}
        <input type="number" min={0.5} step={0.5} value={days} onChange={(e) => setDays(Number(e.target.value))} />
      </label>
    </TryLayout>
  );
}

function TryClaim() {
  const { t } = useTranslation();
  const types = useQuery({ queryKey: ['claims', 'types'], queryFn: api.claims.types });
  const [employeeId, setEmployeeId] = useState('');
  const [typeId, setTypeId] = useState('');
  const [amount, setAmount] = useState(100);
  const type = typeId || types.data?.[0]?.id || '';
  const preview = useQuery({
    queryKey: ['workflow', 'preview', 'CLAIM', employeeId, type, amount],
    queryFn: async (): Promise<Preview> => {
      const p = await api.workflow.claimPreview(employeeId, type, amount);
      return { workflow: p.workflow, fromType: p.fromClaimType, steps: p.steps };
    },
    enabled: !!employeeId && !!type && amount > 0,
  });

  return (
    <TryLayout employeeId={employeeId} setEmployeeId={setEmployeeId} preview={preview} fromTypeText={t('wf.fromClaimType')}>
      <label className="field">
        {t('claims.form.type')}
        <select value={type} onChange={(e) => setTypeId(e.target.value)}>
          {types.data?.map((ct) => <option key={ct.id} value={ct.id}>{ct.name}</option>)}
        </select>
      </label>
      <label className="field">
        {t('fb.amount')}
        <input type="number" min={0.01} step={0.01} inputMode="decimal" value={amount} onChange={(e) => setAmount(Number(e.target.value))} />
        <span className="hint">{t('fb.amountHint')}</span>
      </label>
    </TryLayout>
  );
}

function TryLayout({ employeeId, setEmployeeId, preview, fromTypeText, children }: {
  employeeId: string;
  setEmployeeId: (id: string) => void;
  preview: { data?: Preview; isError: boolean; error: unknown };
  fromTypeText: string;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  const people = useQuery({ queryKey: ['employees', ''], queryFn: () => api.employees() });
  return (
    <div className="wf-try">
      <Card title={t('wf.requestFrom')} padded>
        <label className="field">
          {t('req.employee')}
          <select value={employeeId} onChange={(e) => setEmployeeId(e.target.value)}>
            <option value="">{t('leave.form.choosePerson')}</option>
            {people.data?.employees.map((e) => <option key={e.id} value={e.id}>{e.fullName}{e.department ? ` (${e.department})` : ''}</option>)}
          </select>
        </label>
        {children}
      </Card>

      <Card title={t('wf.wouldGo')}>
        {!employeeId ? (
          <EmptyState icon="search" tone="blue" title={t('wf.chooseWho')}>{t('wf.chooseWhoBody')}</EmptyState>
        ) : preview.isError ? (
          <p className="form-error card-pad" role="alert">{errorText(preview.error)}</p>
        ) : preview.data ? (
          <div className="wf-result">
            <p className="wf-result__flow">
              <Glyph icon="switch" tone={preview.data.fromType ? 'teal' : 'blue'} />
              <span>
                <strong>{preview.data.workflow}</strong>
                <small className="hint">{preview.data.fromType ? fromTypeText : t('wf.firstMatch')}</small>
              </span>
            </p>
            <ol className="wf-steps">
              {preview.data.steps.map((s, i) => (
                <li key={i} className={s.skipped ? 'wf-step is-skipped' : 'wf-step'}>
                  <span className="wf-step__num" aria-hidden="true">{s.skipped ? '–' : s.position}</span>
                  <span className="wf-step__body">
                    <strong>{s.approver}</strong>
                    <small className="hint">{s.skipped ? t('wf.skipped', { note: s.note }) : s.note}</small>
                  </span>
                </li>
              ))}
              <li className="wf-step wf-step--end">
                <span className="wf-step__num wf-step__num--done" aria-hidden="true"><Icon name="check" size={14} /></span>
                <span className="hint">{t('status.APPROVED')}</span>
              </li>
            </ol>
          </div>
        ) : (
          <p className="hint card-pad">{t('wf.working')}</p>
        )}
      </Card>
    </div>
  );
}
