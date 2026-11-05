import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import i18n from '../../i18n';
import { roleText } from '../../lib/modules';
import type { AdminRole, ApprovalFlow, ApprovalFlowInput, EmployeeSummary, FlowStep, FlowStepKind, FlowSubject } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { Glyph, type Tone } from '../../components/Glyph';
import { Icon, type IconName } from '../../components/Icon';
import { Avatar, Card, Status, Toggle } from '../../components/app/kit';

// Labels are read at render time (getters), so they follow the current language.
const kind = (k: FlowStepKind, icon: IconName, tone: Tone) => ({
  kind: k, icon, tone,
  get label() { return i18n.t(`fb.kinds.${k}.label`); },
  get explain() { return i18n.t(`fb.kinds.${k}.explain`); },
});
const KINDS = [kind('MANAGER', 'users', 'blue'), kind('PERSON', 'check', 'green'), kind('ROLE', 'shield', 'purple'), kind('HR', 'building', 'pink')];
const level = (n: 1 | 2 | 3) => ({ level: n, get label() { return i18n.t(`fb.levels.l${n}`); } });
const LEVELS = [level(1), level(2), level(3)];

function kindMeta(kind: FlowStepKind) {
  return KINDS.find((k) => k.kind === kind) ?? KINDS[0];
}

function blankFlow(): ApprovalFlowInput {
  return { name: '', description: '', active: true, typeIds: [], departmentIds: [], minQuantity: null, maxQuantity: null,
    steps: [{ kind: 'MANAGER', managerLevel: 1 }] };
}

function toInput(f: ApprovalFlow): ApprovalFlowInput {
  return { name: f.name, description: f.description ?? '', active: f.active, typeIds: f.typeIds, departmentIds: f.departmentIds,
    minQuantity: f.minQuantity === null ? null : Number(f.minQuantity), maxQuantity: f.maxQuantity === null ? null : Number(f.maxQuantity),
    steps: f.steps.map((s) => ({ ...s })) };
}

/** Leave or claim types, as id → name, for the trigger card. */
function useTypeNames(subject: FlowSubject) {
  const leave = useQuery({ queryKey: ['leave', 'settings', 'types'], queryFn: api.leave.settings.types, enabled: subject === 'LEAVE' });
  const claims = useQuery({ queryKey: ['claims', 'settings', 'types'], queryFn: api.claims.settings.types, enabled: subject === 'CLAIM' });
  const list = subject === 'CLAIM' ? claims.data : leave.data;
  return new Map((list ?? []).map((x) => [x.id, x.name]));
}

/** "Amount 100–500", "5+ days": a workflow's quantity condition in words. */
function quantityText(subject: FlowSubject, min: number | null, max: number | null) {
  const t = i18n.t;
  if (subject === 'CLAIM') {
    const n = (v: number) => Number(v).toLocaleString(undefined, { minimumFractionDigits: 0, maximumFractionDigits: 2 });
    return min !== null && max !== null ? t('fb.amountRange', { min: n(min), max: n(max) })
      : min !== null ? t('fb.amountMin', { min: n(min) }) : max !== null ? t('fb.amountMax', { max: n(max) }) : t('fb.anyAmount');
  }
  return min !== null && max !== null ? t('fb.daysRange', { min, max })
    : min !== null ? t('fb.daysMin', { min }) : max !== null ? t('fb.daysMax', { max }) : t('fb.anyLength');
}

/** Left column: workflows in the order they're checked, with reordering. */
export function FlowList({ subject, flows, selectedId, onSelect, onNew }: {
  subject: FlowSubject; flows: ApprovalFlow[]; selectedId: string | null; onSelect: (id: string) => void; onNew: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const reorder = useMutation({
    mutationFn: (order: string[]) => api.workflow.reorderFlows(subject, order),
    onSuccess: (data) => queryClient.setQueryData(['workflow', 'flows', subject], data),
  });
  const move = (index: number, delta: number) => {
    const order = flows.map((f) => f.id);
    const [item] = order.splice(index, 1);
    order.splice(index + delta, 0, item);
    reorder.mutate(order);
  };

  return (
    <Card title={t('wf.tabFlows')} count={flows.length}>
      <p className="hint card-pad">{t('fb.order')}</p>
      {reorder.error && <p className="form-error card-pad" role="alert">{errorText(reorder.error)}</p>}
      <ol className="fl-list">
        {flows.map((f, i) => (
          <li key={f.id} className={f.id === selectedId ? 'fl-item is-selected' : 'fl-item'}>
            <button type="button" className="fl-item__main" aria-current={f.id === selectedId} onClick={() => onSelect(f.id)}>
              <span className="fl-item__pos" aria-hidden="true">{i + 1}</span>
              <span className="fl-item__text">
                <span className="fl-item__name">{f.name}</span>
                <span className="fl-item__meta">
                  <span className={f.active ? 'fl-dot is-on' : 'fl-dot'} aria-hidden="true" />
                  {f.active ? t('common.on') : t('common.off')} · {t('fb.nSteps', { count: f.steps.length })}
                </span>
              </span>
            </button>
            <span className="fl-item__move">
              <button type="button" onClick={() => move(i, -1)} disabled={i === 0 || reorder.isPending} aria-label={t('fb.earlier', { name: f.name })}>
                <Icon name="chevron" size={12} style={{ transform: 'rotate(-90deg)' }} />
              </button>
              <button type="button" onClick={() => move(i, 1)} disabled={i === flows.length - 1 || reorder.isPending} aria-label={t('fb.later', { name: f.name })}>
                <Icon name="chevron" size={12} style={{ transform: 'rotate(90deg)' }} />
              </button>
            </span>
          </li>
        ))}
        {selectedId === 'new' && (
          <li className="fl-item is-selected">
            <span className="fl-item__main"><span className="fl-item__pos" aria-hidden="true">+</span>
              <span className="fl-item__text"><span className="fl-item__name">{t('wf.new')}</span><span className="fl-item__meta">{t('roles.notSaved')}</span></span></span>
          </li>
        )}
      </ol>
      <div className="fl-list__foot">
        <button type="button" className="btn small" onClick={onNew}><Icon name="plus" size={14} />{t('wf.new')}</button>
        <div className="fl-fallback">
          <Glyph icon={subject === 'CLAIM' ? 'receipt' : 'calendar'} tone={subject === 'CLAIM' ? 'orange' : 'teal'} size="sm" />
          <span>{subject === 'CLAIM' ? t('fb.fallbackClaim') : t('fb.fallback')}</span>
        </div>
      </div>
    </Card>
  );
}

/** Right column: the workflow as a flow of cards: the trigger, each approval step, then "Approved". */
export function FlowEditor({ subject, flow, onSaved, onDeleted }: {
  subject: FlowSubject; flow: ApprovalFlow | null; onSaved: (id: string) => void; onDeleted: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const typeNames = useTypeNames(subject);
  const departments = useQuery({ queryKey: ['admin', 'departments'], queryFn: api.admin.departments });
  const roles = useQuery({ queryKey: ['admin', 'roles'], queryFn: api.admin.roles });
  const people = useQuery({ queryKey: ['employees', ''], queryFn: () => api.employees() });
  const [input, setInput] = useState<ApprovalFlowInput>(flow ? toInput(flow) : blankFlow());
  // Which card is open: 'trigger', a step index, or nothing.
  const [open, setOpen] = useState<'trigger' | number | null>(flow ? null : 'trigger');
  const set = <K extends keyof ApprovalFlowInput>(k: K, v: ApprovalFlowInput[K]) => setInput((x) => ({ ...x, [k]: v }));

  const done = async () => {
    await queryClient.invalidateQueries({ queryKey: ['workflow'] });
  };
  const save = useMutation({
    mutationFn: () => (flow ? api.workflow.updateFlow(flow.id, input) : api.workflow.createFlow(subject, input)),
    onSuccess: async (saved) => {
      await done();
      onSaved(saved.id);
    },
  });
  const remove = useMutation({
    mutationFn: () => api.workflow.deleteFlow(flow!.id),
    onSuccess: async () => {
      await done();
      onDeleted();
    },
  });
  const error = save.error ?? remove.error;

  const insertStep = (at: number) => {
    const steps = [...input.steps];
    steps.splice(at, 0, { kind: 'MANAGER', managerLevel: 1 });
    set('steps', steps);
    setOpen(at);
  };
  const moveStep = (i: number, delta: number) => {
    const steps = [...input.steps];
    const [s] = steps.splice(i, 1);
    steps.splice(i + delta, 0, s);
    set('steps', steps);
    setOpen(i + delta);
  };
  const removeStep = (i: number) => {
    set('steps', input.steps.filter((_, j) => j !== i));
    setOpen(null);
  };
  const changeStep = (i: number, step: FlowStep) => set('steps', input.steps.map((s, j) => (j === i ? step : s)));
  const canAdd = input.steps.length < 8;

  return (
    <Card>
      <form
        className="fb"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <header className="fb-head">
          <div className="fb-head__text">
            <input className="fb-title" value={input.name} onChange={(e) => set('name', e.target.value)} placeholder={t('fb.namePlaceholder')}
              aria-label={t('fb.name')} required maxLength={80} />
            <input className="fb-desc" value={input.description ?? ''} onChange={(e) => set('description', e.target.value)}
              placeholder={t('fb.descPlaceholder')} aria-label={t('roles.description')} />
          </div>
          <Toggle checked={input.active} onChange={(on) => set('active', on)} label={input.active ? t('common.on') : t('common.off')} />
        </header>

        {error && <p className="form-error fb-error" role="alert">{errorText(error)}</p>}

        <ol className="fb-flow">
          <li>
            <TriggerCard
              subject={subject}
              input={input}
              set={set}
              open={open === 'trigger'}
              onToggle={() => setOpen(open === 'trigger' ? null : 'trigger')}
              typeNames={typeNames}
              deptNames={new Map((departments.data ?? []).map((d) => [d.id, d.name]))}
            />
          </li>
          <li className="fb-gap"><Connector onAdd={canAdd ? () => insertStep(0) : undefined} /></li>
          {input.steps.map((s, i) => (
            <li key={i} className="fb-node">
              <StepCard
                index={i}
                step={s}
                total={input.steps.length}
                open={open === i}
                onToggle={() => setOpen(open === i ? null : i)}
                onChange={(step) => changeStep(i, step)}
                onMove={(d) => moveStep(i, d)}
                onRemove={() => removeStep(i)}
                people={people.data?.employees ?? []}
                roles={roles.data ?? []}
              />
              <Connector onAdd={canAdd ? () => insertStep(i + 1) : undefined} />
            </li>
          ))}
          <li className="fb-end">
            <span className="fb-end__icon" aria-hidden="true"><Icon name="check" size={16} /></span>
            <span><strong>{t('status.APPROVED')}</strong><small>{t('fb.endHint')}</small></span>
          </li>
        </ol>

        <footer className="fb-foot">
          <button type="submit" className="btn primary" disabled={save.isPending}>{save.isPending ? t('common.saving') : flow ? t('fb.save') : t('fb.create')}</button>
          {flow && <button type="button" className="btn reject" onClick={() => remove.mutate()} disabled={remove.isPending}>{t('common.delete')}</button>}
          <span className="hint">{t('fb.keepSteps')}</span>
        </footer>
      </form>
    </Card>
  );
}

function Connector({ onAdd }: { onAdd?: () => void }) {
  const { t } = useTranslation();
  return (
    <div className="fb-connector">
      <span className="fb-connector__line" />
      {onAdd && (
        <button type="button" className="fb-connector__add" onClick={onAdd} aria-label={t('fb.addHere')} title={t('fb.addHere')}>
          <Icon name="plus" size={14} />
        </button>
      )}
    </div>
  );
}

function TriggerCard({ subject, input, set, open, onToggle, typeNames, deptNames }: {
  subject: FlowSubject;
  input: ApprovalFlowInput;
  set: <K extends keyof ApprovalFlowInput>(k: K, v: ApprovalFlowInput[K]) => void;
  open: boolean;
  onToggle: () => void;
  typeNames: Map<string, string>;
  deptNames: Map<string, string>;
}) {
  const { t } = useTranslation();
  const toggleIn = (key: 'typeIds' | 'departmentIds', id: string) =>
    set(key, input[key].includes(id) ? input[key].filter((x) => x !== id) : [...input[key], id]);
  const num = (v: string) => (v === '' ? null : Number(v));
  const claim = subject === 'CLAIM';
  const summary = [
    input.typeIds.length
      ? input.typeIds.map((id) => typeNames.get(id) ?? (claim ? t('nav.claims') : t('nav.leave'))).join(', ')
      : claim ? t('fb.anyClaimType') : t('fb.anyType'),
    quantityText(subject, input.minQuantity, input.maxQuantity),
    input.departmentIds.length ? input.departmentIds.map((id) => deptNames.get(id) ?? t('reports.department')).join(', ') : t('fb.allDepts'),
  ];

  return (
    <div className={open ? 'fb-card fb-card--trigger is-open' : 'fb-card fb-card--trigger'}>
      <button type="button" className="fb-card__head" onClick={onToggle} aria-expanded={open}>
        <span className="fb-card__icon fb-card__icon--trigger" aria-hidden="true"><Icon name="inbox" size={16} /></span>
        <span className="fb-card__text">
          <span className="fb-card__eyebrow">{claim ? t('fb.whenClaim') : t('fb.when')}</span>
          <span className="fb-card__title">{summary.join(' · ')}</span>
        </span>
        <span className="fb-card__edit">{open ? t('users.done') : t('common.edit')}</span>
      </button>
      {open && (
        <div className="fb-card__body">
          <span className="wf-label">{claim ? t('claims.form.type') : t('leave.form.type')}</span>
          <div className="chip-picker" role="group" aria-label={claim ? t('cs.types') : t('ls.tabs.types')}>
            <label className={input.typeIds.length === 0 ? 'is-on' : undefined}>
              <input type="checkbox" checked={input.typeIds.length === 0} onChange={() => set('typeIds', [])} />{claim ? t('fb.allClaimTypes') : t('reports.allTypes')}
            </label>
            {[...typeNames].map(([id, name]) => (
              <label key={id} className={input.typeIds.includes(id) ? 'is-on' : undefined}>
                <input type="checkbox" checked={input.typeIds.includes(id)} onChange={() => toggleIn('typeIds', id)} />{name}
              </label>
            ))}
          </div>
          <span className="wf-label">{t('fb.deptOf')}</span>
          <div className="chip-picker" role="group" aria-label={t('users.departments')}>
            <label className={input.departmentIds.length === 0 ? 'is-on' : undefined}>
              <input type="checkbox" checked={input.departmentIds.length === 0} onChange={() => set('departmentIds', [])} />{t('reports.allDepts')}
            </label>
            {[...deptNames].map(([id, name]) => (
              <label key={id} className={input.departmentIds.includes(id) ? 'is-on' : undefined}>
                <input type="checkbox" checked={input.departmentIds.includes(id)} onChange={() => toggleIn('departmentIds', id)} />{name}
              </label>
            ))}
          </div>
          <span className="wf-label">{claim ? t('fb.amount') : t('fb.length')}</span>
          <div className="fb-days">
            <label className="field">{t('fb.atLeast')}<input type="number" min={0} step={claim ? 0.01 : 0.5} inputMode="decimal" value={input.minQuantity ?? ''} onChange={(e) => set('minQuantity', num(e.target.value))} placeholder={t('fb.any')} /></label>
            <span className="hint">{t('fb.to')}</span>
            <label className="field">{t('fb.atMost')}<input type="number" min={0} step={claim ? 0.01 : 0.5} inputMode="decimal" value={input.maxQuantity ?? ''} onChange={(e) => set('maxQuantity', num(e.target.value))} placeholder={t('fb.any')} /></label>
            {!claim && <span className="hint">{t('fb.workingDays')}</span>}
          </div>
          {claim && <p className="hint">{t('fb.amountHint')}</p>}
        </div>
      )}
    </div>
  );
}

function StepCard({ index, step, total, open, onToggle, onChange, onMove, onRemove, people, roles }: {
  index: number; step: FlowStep; total: number; open: boolean; onToggle: () => void; onChange: (s: FlowStep) => void;
  onMove: (delta: number) => void; onRemove: () => void; people: EmployeeSummary[]; roles: AdminRole[];
}) {
  const { t } = useTranslation();
  const meta = kindMeta(step.kind);
  const person = people.find((p) => p.id === step.employeeId);
  const role = roles.find((r) => r.id === step.roleId);
  const title = step.kind === 'MANAGER' ? LEVELS.find((l) => l.level === (step.managerLevel ?? 1))?.label ?? t('common.manager')
    : step.kind === 'PERSON' ? person?.fullName ?? t('fb.choosePerson')
      : step.kind === 'ROLE' ? role ? t('fb.anyoneWith', { role: roleText(role).name }) : t('fb.chooseRole')
        : t('ls.chainHr');
  const incomplete = (step.kind === 'PERSON' && !step.employeeId) || (step.kind === 'ROLE' && !step.roleId);
  const setKind = (kind: FlowStepKind) =>
    onChange(kind === 'MANAGER' ? { kind, managerLevel: 1 } : kind === 'PERSON' ? { kind, employeeId: null } : kind === 'ROLE' ? { kind, roleId: null } : { kind });

  return (
    <div className={['fb-card', open && 'is-open', incomplete && 'is-incomplete'].filter(Boolean).join(' ')}>
      <div className="fb-card__row">
        <button type="button" className="fb-card__head" onClick={onToggle} aria-expanded={open}>
          <span className="fb-card__num" aria-hidden="true">{index + 1}</span>
          {step.kind === 'PERSON' && person ? <Avatar name={person.fullName} size={32} /> : <Glyph icon={meta.icon} tone={meta.tone} size="lg" />}
          <span className="fb-card__text">
            <span className="fb-card__eyebrow">{t('fb.step', { n: index + 1 })} · {meta.label}</span>
            <span className="fb-card__title">{title}</span>
          </span>
          {incomplete && <Status tone="amber">{t('fb.needsChoice')}</Status>}
        </button>
        <span className="fb-card__tools">
          <button type="button" onClick={() => onMove(-1)} disabled={index === 0} aria-label={t('fb.moveUp', { n: index + 1 })}>
            <Icon name="chevron" size={14} style={{ transform: 'rotate(-90deg)' }} />
          </button>
          <button type="button" onClick={() => onMove(1)} disabled={index === total - 1} aria-label={t('fb.moveDown', { n: index + 1 })}>
            <Icon name="chevron" size={14} style={{ transform: 'rotate(90deg)' }} />
          </button>
          <button type="button" onClick={onRemove} disabled={total === 1} aria-label={t('fb.removeStep', { n: index + 1 })}>
            <Icon name="close" size={14} />
          </button>
        </span>
      </div>
      {open && (
        <div className="fb-card__body">
          <div className="fb-kinds" role="radiogroup" aria-label={t('fb.whoApproves', { n: index + 1 })}>
            {KINDS.map((k) => (
              <label key={k.kind} className={step.kind === k.kind ? 'fb-kind is-on' : 'fb-kind'}>
                <input type="radio" name={`kind-${index}`} checked={step.kind === k.kind} onChange={() => setKind(k.kind)} />
                <Glyph icon={k.icon} tone={k.tone} size="sm" />
                <span>{k.label}</span>
              </label>
            ))}
          </div>
          <p className="hint">{meta.explain}</p>
          {step.kind === 'MANAGER' && (
            <div className="segmented" role="radiogroup" aria-label={t('fb.managerLevel')}>
              {LEVELS.map((l) => (
                <label key={l.level} className={(step.managerLevel ?? 1) === l.level ? 'is-on' : undefined}>
                  <input type="radio" name={`level-${index}`} checked={(step.managerLevel ?? 1) === l.level} onChange={() => onChange({ kind: 'MANAGER', managerLevel: l.level })} />
                  {l.label}
                </label>
              ))}
            </div>
          )}
          {step.kind === 'PERSON' && (
            <select className="input fb-select" value={step.employeeId ?? ''} onChange={(e) => onChange({ kind: 'PERSON', employeeId: e.target.value || null })} aria-label={t('users.person')} required>
              <option value="">{t('leave.form.choosePerson')}</option>
              {people.filter((p) => p.status === 'ACTIVE').map((p) => <option key={p.id} value={p.id}>{p.fullName}{p.department ? ` · ${p.department}` : ''}</option>)}
            </select>
          )}
          {step.kind === 'ROLE' && (
            <select className="input fb-select" value={step.roleId ?? ''} onChange={(e) => onChange({ kind: 'ROLE', roleId: e.target.value || null })} aria-label={t('fb.role')} required>
              <option value="">{t('fb.chooseRoleDots')}</option>
              {roles.map((r) => <option key={r.id} value={r.id}>{roleText(r).name} · {t('common.person', { count: r.userCount })}</option>)}
            </select>
          )}
          <p className="hint">{t('fb.skipHint')}</p>
        </div>
      )}
    </div>
  );
}
