import { useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { ApprovalFlow, FlowAssignment } from '@hrms/api-client';
import { api } from '../../lib/api';
import { can, errorText, useAccess } from '../../lib/access';
import { coveringFlow, coversAll, flowsFor, useSubjectFlows } from './typeFlows';

/**
 * A leave or claim type's Approval settings: the approval workflows that come before the type's own chain, then the
 * chain itself ({@code children}), labelled as the fallback. Workflows are matched when a request is sent; the chain
 * is only used when none match, so while a workflow takes every request it's shown dimmed (still editable, for when
 * that workflow is switched off).
 */
export function TypeWorkflows({ subject, typeId, children }: { subject: 'LEAVE' | 'CLAIM'; typeId: string | null; children: ReactNode }) {
  const { t } = useTranslation();
  const { user } = useAccess();
  const flows = useSubjectFlows(subject);
  const manage = can(user, 'workflow:manage');
  const base = `/app/admin/workflows${subject === 'CLAIM' ? '?for=claims' : ''}`;
  const open = (id: string) => `/app/admin/workflows?${subject === 'CLAIM' ? 'for=claims&' : ''}open=${id}`;
  if (!flows.data) return <>{children}</>;

  const applicable = flowsFor(flows.data, typeId);
  const covering = coveringFlow(applicable);
  const what = subject === 'CLAIM' ? t('tw.claims') : t('tw.leave');

  return (
    <>
    <div className="notice type-flows" role="note">
      {applicable.length === 0 ? (
        <p>
          {t('tw.none', { what })}
          {manage && <> <Link to={base}>{t('tw.setUp')}</Link></>}
        </p>
      ) : (
        <>
          <p>{covering ? t('tw.covered', { name: covering.name, what }) : t('tw.some', { what })}</p>
          <ul>
            {applicable.map((f) => (
              <li key={f.id}>
                {manage ? <Link to={open(f.id)}>{f.name}</Link> : <strong>{f.name}</strong>}
                <span className="muted"> · {coversAll(f) ? t('tw.every', { what }) : t('tw.someConditions')}</span>
              </li>
            ))}
          </ul>
        </>
      )}
      {manage && flows.data.length > 0 && (typeId
        ? <ChooseWorkflow subject={subject} typeId={typeId} flows={flows.data} current={applicable[0]?.id ?? null} />
        : <p className="hint">{t('tw.saveFirst')}</p>)}
    </div>
    {applicable.length === 0 ? children : (
      <div className={covering ? 'type-flows__fallback is-unused' : 'type-flows__fallback'}>
        <span className="wf-label">{covering ? t('tw.fallbackUnused', { name: covering.name }) : t('tw.fallback')}</span>
        {children}
      </div>
    )}
    </>
  );
}

/**
 * "Use this workflow for this type": shows what choosing one would change (adding the type to it, moving it up,
 * taking the type out of workflows above it), then applies it. Applied straight away, apart from saving the type.
 */
function ChooseWorkflow({ subject, typeId, flows, current }: {
  subject: 'LEAVE' | 'CLAIM'; typeId: string; flows: ApprovalFlow[]; current: string | null;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [picked, setPicked] = useState<string | null>(null);
  const [preview, setPreview] = useState<FlowAssignment | null>(null);
  const ordered = [...flows].sort((a, b) => a.position - b.position);
  const name = (id: string | null) => flows.find((f) => f.id === id)?.name ?? '';

  const check = useMutation({
    mutationFn: (flowId: string) => api.workflow.assignFlow(subject, typeId, flowId, true),
    onSuccess: setPreview,
  });
  const apply = useMutation({
    mutationFn: (flowId: string) => api.workflow.assignFlow(subject, typeId, flowId, false),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['workflow', 'flows', subject] });
      setPicked(null);
      setPreview(null);
    },
  });
  const choose = (id: string) => {
    setPreview(null);
    apply.reset();
    if (!id || id === current) {
      setPicked(null);
      return;
    }
    setPicked(id);
    check.mutate(id);
  };
  const error = check.error ?? apply.error;

  return (
    <div className="type-flows__choose">
      <label className="field">
        {t('tw.pick')}
        <select value={picked ?? current ?? ''} onChange={(e) => choose(e.target.value)} disabled={apply.isPending}>
          {!current && <option value="">{t('tw.pickNone')}</option>}
          {ordered.map((f) => <option key={f.id} value={f.id}>{f.active ? f.name : t('tw.off', { name: f.name })}</option>)}
        </select>
      </label>
      {error && <p className="form-error" role="alert">{errorText(error)}</p>}
      {picked && preview && (
        preview.changes.length === 0 ? (
          <p className="hint">{t('tw.already', { name: name(picked) })}</p>
        ) : (
          <div className="type-flows__preview" role="status">
            <p>{t('tw.willDo', { name: name(picked) })}</p>
            <ul>{preview.changes.map((c, i) => <li key={i}>{t(`tw.change.${c.kind}`, { flow: c.flow })}</li>)}</ul>
            {preview.warnings.length > 0 && (
              <ul className="type-flows__warnings">{preview.warnings.map((w, i) => <li key={i}>{t(`tw.warn.${w.kind}`, { flow: w.flow })}</li>)}</ul>
            )}
            <div className="panel-actions">
              <button type="button" className="btn primary small" disabled={apply.isPending} onClick={() => apply.mutate(picked)}>{t('tw.apply')}</button>
              <button type="button" className="btn small" disabled={apply.isPending} onClick={() => choose('')}>{t('common.cancel')}</button>
            </div>
            <p className="hint">{t('tw.appliedNow')}</p>
          </div>
        )
      )}
    </div>
  );
}
