import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { Glyph } from '../../components/Glyph';
import { Icon } from '../../components/Icon';
import { Card, EmptyState, PageHeader, Tabs } from '../../components/app/kit';
import { FlowEditor, FlowList } from './FlowBuilder';
import './workflows.css';

const TABS = ['flows', 'try'] as const;
type Tab = (typeof TABS)[number];

/** Approval workflow settings: build who approves which leave requests, in what order, and try it out. */
export function WorkflowsPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab: Tab = (TABS as readonly string[]).includes(params.get('tab') ?? '') ? (params.get('tab') as Tab) : 'flows';
  const flows = useQuery({ queryKey: ['workflow', 'flows'], queryFn: api.workflow.flows });
  const [selected, setSelected] = useState<string | 'new' | null>(null);
  const current = selected === 'new' ? null : flows.data?.find((f) => f.id === selected) ?? flows.data?.[0] ?? null;

  return (
    <>
      <PageHeader
        title={t('nav.workflows')}
        subtitle={tab === 'flows' ? t('wf.subtitle') : t('wf.trySubtitle')}
        actions={tab === 'flows' && <button type="button" className="btn primary" onClick={() => setSelected('new')}><Icon name="plus" size={16} />{t('wf.new')}</button>}
      />
      {flows.isError && <p className="form-error" role="alert">{errorText(flows.error)}</p>}

      <Tabs titled
        label={t('nav.workflows')}
        value={tab}
        onChange={(key) => setParams(key === 'flows' ? {} : { tab: key }, { replace: true })}
        tabs={[
          { key: 'flows', label: t('wf.tabFlows'), icon: 'switch', count: flows.data?.length },
          { key: 'try', label: t('wf.tabTry'), icon: 'search' },
        ]}
      >
        {tab === 'flows' && (
          <div className="wf-layout">
            <FlowList flows={flows.data ?? []} selectedId={selected === 'new' ? 'new' : current?.id ?? null} onSelect={setSelected} onNew={() => setSelected('new')} />
            {selected === 'new' || current ? (
              <FlowEditor
                key={selected === 'new' ? 'new' : current!.id}
                flow={selected === 'new' ? null : current}
                onSaved={(id) => setSelected(id)}
                onDeleted={() => setSelected(null)}
              />
            ) : (
              <Card>
                <EmptyState icon="switch" tone="blue" title={t('wf.noneTitle')}
                  action={<button type="button" className="btn primary" onClick={() => setSelected('new')}>{t('wf.buildFirst')}</button>}>
                  {t('wf.noneBody')}
                </EmptyState>
              </Card>
            )}
          </div>
        )}
        {tab === 'try' && <TryIt />}
      </Tabs>
    </>
  );
}

function TryIt() {
  const { t } = useTranslation();
  const people = useQuery({ queryKey: ['employees', ''], queryFn: () => api.employees() });
  const types = useQuery({ queryKey: ['leave', 'types'], queryFn: api.leave.types });
  const [employeeId, setEmployeeId] = useState('');
  const [typeId, setTypeId] = useState('');
  const [days, setDays] = useState(1);
  const type = typeId || types.data?.[0]?.id || '';
  const preview = useQuery({
    queryKey: ['workflow', 'preview', employeeId, type, days],
    queryFn: () => api.workflow.preview(employeeId, type, days),
    enabled: !!employeeId && !!type && days > 0,
  });

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
      </Card>

      <Card title={t('wf.wouldGo')}>
        {!employeeId ? (
          <EmptyState icon="search" tone="blue" title={t('wf.chooseWho')}>{t('wf.chooseWhoBody')}</EmptyState>
        ) : preview.isError ? (
          <p className="form-error card-pad" role="alert">{errorText(preview.error)}</p>
        ) : preview.data ? (
          <div className="wf-result">
            <p className="wf-result__flow">
              <Glyph icon="switch" tone={preview.data.fromLeaveType ? 'teal' : 'blue'} />
              <span>
                <strong>{preview.data.workflow}</strong>
                <small className="hint">{preview.data.fromLeaveType ? t('wf.fromType') : t('wf.firstMatch')}</small>
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
