import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { OvertimeRule, OvertimeRuleInput, OvertimeRuleSettings } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { Card, EmptyState, List, PageHeader, Status } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Glyph } from '../../components/Glyph';
import { Icon } from '../../components/Icon';
import { num, ruleText } from './overtimeText';
import './attendance.css';

type Scope = 'COMPANY' | 'ENTITY' | 'DEPARTMENT';

const EMPTY: OvertimeRuleInput = {
  entityId: null, departmentId: null, compensation: 'TIME_OFF', conversion: 'BANKED', creditUnit: 'HALF_DAY',
  hoursPerUnit: 4, applyRate: false, leaveTypeId: null, expiry: 'NONE', expiryDays: null,
  rateWorking: 1.5, rateRest: 2, rateHoliday: 3, rateMode: 'AUTO',
};

function toInput(r: OvertimeRule): OvertimeRuleInput {
  return {
    entityId: r.entityId, departmentId: r.departmentId, compensation: r.compensation, conversion: r.conversion,
    creditUnit: r.creditUnit, hoursPerUnit: Number(r.hoursPerUnit), applyRate: r.applyRate, leaveTypeId: r.leaveTypeId,
    expiry: r.expiry, expiryDays: r.expiryDays,
    rateWorking: Number(r.rateWorking), rateRest: Number(r.rateRest), rateHoliday: Number(r.rateHoliday), rateMode: r.rateMode,
  };
}

/** The input as a rule, to describe it in words while editing. */
function asRule(input: OvertimeRuleInput, leaveTypes: OvertimeRuleSettings['leaveTypes']): OvertimeRule {
  return {
    ...input, id: null, scope: 'COMPANY', entityName: null, departmentName: null, updatedAt: null,
    leaveTypeName: leaveTypes.find((x) => x.id === input.leaveTypeId)?.name ?? null,
  };
}

/**
 * /app/attendance/overtime-rules (attendance:manage): whether overtime is paid, given back as time off, or the employee's
 * choice, and how hours become leave. One company-wide rule, with optional rules for a company entity or a department;
 * a department's rule wins over its entity's, which wins over the company's. Without any rule overtime is paid.
 */
export function OvertimeRulesPage() {
  const { t } = useTranslation();
  const settings = useQuery({ queryKey: ['attendance', 'overtime-rules'], queryFn: api.attendance.overtime.rules.list });
  const [editing, setEditing] = useState<OvertimeRule | 'new' | null>(null);
  const rules = settings.data?.rules ?? [];
  const company = rules.find((r) => r.scope === 'COMPANY');

  return (
    <>
      <PageHeader
        title={t('nav.overtimeRules')}
        subtitle={t('otRules.subtitle')}
        actions={<button type="button" className="btn primary" onClick={() => setEditing('new')}><Icon name="plus" size={16} />{t('otRules.add')}</button>}
      />
      {settings.isError && <p className="form-error" role="alert">{errorText(settings.error)}</p>}
      <Card title={t('otRules.title')} count={rules.length}>
        <p className="muted card-pad">{t('otRules.order')}</p>
        {settings.data && rules.length === 0 ? (
          <EmptyState icon="star" tone="purple" title={t('otRules.noneTitle')}
            action={<button type="button" className="btn primary" onClick={() => setEditing('new')}>{t('otRules.setUp')}</button>}>
            {t('otRules.noneBody')}
          </EmptyState>
        ) : (
          <List>
            {!company && settings.data && (
              <li className="ui-row">
                <span className="ui-row__lead"><Glyph icon="building" tone="blue" /></span>
                <span className="ui-row__text">
                  <span className="ui-row__title">{t('otRules.scope.COMPANY')}</span>
                  <span className="ui-row__meta">{t('otRules.summary.pay')} · {t('otRules.builtIn')}</span>
                </span>
              </li>
            )}
            {rules.map((r) => (
              <li key={r.id} className="ui-row">
                <span className="ui-row__lead">
                  <Glyph icon={r.scope === 'DEPARTMENT' ? 'users' : 'building'} tone={r.scope === 'COMPANY' ? 'blue' : r.scope === 'ENTITY' ? 'teal' : 'purple'} />
                </span>
                <span className="ui-row__text">
                  <span className="ui-row__title">
                    {r.scope === 'COMPANY' ? t('otRules.scope.COMPANY')
                      : r.scope === 'ENTITY' ? t('otRules.forEntity', { name: r.entityName }) : t('otRules.forDepartment', { name: r.departmentName })}
                  </span>
                  <span className="ui-row__meta">{ruleText(t, r)}</span>
                </span>
                <span className="ui-row__trail">
                  <Status tone={r.compensation === 'PAY' ? 'grey' : r.compensation === 'TIME_OFF' ? 'green' : 'blue'}>{t(`otRules.comp.${r.compensation}`)}</Status>
                  <button type="button" className="btn small" onClick={() => setEditing(r)}>{t('common.edit')}</button>
                </span>
              </li>
            ))}
          </List>
        )}
      </Card>
      {editing && settings.data && (
        <RuleEditor key={editing === 'new' ? 'new' : editing.id} rule={editing === 'new' ? null : editing} settings={settings.data}
          hasCompany={!!company} onClose={() => setEditing(null)} />
      )}
    </>
  );
}

function RuleEditor({ rule, settings, hasCompany, onClose }: {
  rule: OvertimeRule | null; settings: OvertimeRuleSettings; hasCompany: boolean; onClose: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [input, setInput] = useState<OvertimeRuleInput>(() => rule ? toInput(rule)
    : { ...EMPTY, leaveTypeId: settings.leaveTypes[0]?.id ?? null });
  const [scope, setScope] = useState<Scope>(rule ? (rule.scope as Scope) : hasCompany ? (settings.departments.length ? 'DEPARTMENT' : 'ENTITY') : 'COMPANY');
  const set = <K extends keyof OvertimeRuleInput>(k: K, v: OvertimeRuleInput[K]) => setInput((x) => ({ ...x, [k]: v }));
  const pay = input.compensation === 'PAY';

  const body = (): OvertimeRuleInput => ({
    ...input,
    entityId: scope === 'ENTITY' ? input.entityId ?? settings.entities[0]?.id ?? null : null,
    departmentId: scope === 'DEPARTMENT' ? input.departmentId ?? settings.departments[0]?.id ?? null : null,
    expiryDays: input.expiry === 'DAYS' ? input.expiryDays : null,
  });
  const done = async () => {
    await queryClient.invalidateQueries({ queryKey: ['attendance'] });
    onClose();
  };
  const save = useMutation({
    mutationFn: () => (rule?.id ? api.attendance.overtime.rules.update(rule.id, body()) : api.attendance.overtime.rules.create(body())),
    onSuccess: done,
  });
  const remove = useMutation({ mutationFn: () => api.attendance.overtime.rules.remove(rule!.id!), onSuccess: done });

  const hoursOk = Number.isFinite(input.hoursPerUnit) && input.hoursPerUnit > 0 && input.hoursPerUnit <= 24 && Number.isInteger(input.hoursPerUnit * 4);
  const okRate = (v: number) => Number.isFinite(v) && v >= 1 && v <= 5 && Number.isInteger(v * 4);
  const ratesUsed = pay || input.compensation === 'CHOICE' || input.applyRate;
  const ratesOk = !ratesUsed || (okRate(input.rateWorking) && okRate(input.rateRest) && okRate(input.rateHoliday));
  const valid = ratesOk && (pay || (!!input.leaveTypeId && hoursOk && (input.expiry !== 'DAYS' || (!!input.expiryDays && input.expiryDays >= 1 && input.expiryDays <= 730))));
  const unitWord = input.creditUnit === 'FULL_DAY' ? t('otRules.aFullDay') : t('otRules.aHalfDay');

  return (
    <Panel title={rule ? t('otRules.editTitle') : t('otRules.newTitle')} onClose={onClose}>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
        {(save.error || remove.error) && <p className="form-error" role="alert">{errorText(save.error ?? remove.error)}</p>}

        <span className="wf-label" id="otr-scope">{t('otRules.appliesTo')}</span>
        <div className="segmented" role="radiogroup" aria-labelledby="otr-scope">
          {(['COMPANY', 'ENTITY', 'DEPARTMENT'] as const).map((s) => (
            <label key={s} className={scope === s ? 'is-on' : undefined}>
              <input type="radio" name="otr-scope" checked={scope === s} onChange={() => setScope(s)}
                disabled={(s === 'ENTITY' && settings.entities.length === 0) || (s === 'DEPARTMENT' && settings.departments.length === 0)} />
              {t(`otRules.scope.${s}`)}
            </label>
          ))}
        </div>
        {scope === 'ENTITY' && (
          <label className="field">
            {t('otRules.entity')}
            <select value={input.entityId ?? settings.entities[0]?.id ?? ''} onChange={(e) => set('entityId', e.target.value)}>
              {settings.entities.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </select>
          </label>
        )}
        {scope === 'DEPARTMENT' && (
          <label className="field">
            {t('otRules.department')}
            <select value={input.departmentId ?? settings.departments[0]?.id ?? ''} onChange={(e) => set('departmentId', e.target.value)}>
              {settings.departments.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </select>
          </label>
        )}

        <span className="wf-label" id="otr-comp">{t('otRules.howCompensated')}</span>
        <div className="segmented" role="radiogroup" aria-labelledby="otr-comp">
          {(['PAY', 'TIME_OFF', 'CHOICE'] as const).map((c) => (
            <label key={c} className={input.compensation === c ? 'is-on' : undefined}>
              <input type="radio" name="otr-comp" checked={input.compensation === c} onChange={() => set('compensation', c)} />
              {t(`otRules.comp.${c}`)}
            </label>
          ))}
        </div>
        <p className="hint">{t(`otRules.compHint.${input.compensation}`)}</p>

        {!pay && (
          <>
            <label className="field">
              {t('otRules.leaveType')}
              <select value={input.leaveTypeId ?? ''} onChange={(e) => set('leaveTypeId', e.target.value || null)} required>
                {settings.leaveTypes.length === 0 && <option value="">{t('otRules.noLeaveTypes')}</option>}
                {settings.leaveTypes.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
              </select>
            </label>
            <p className="hint">{t('otRules.leaveTypeHint')}</p>

            <span className="wf-label" id="otr-unit">{t('otRules.earns')}</span>
            <div className="ot-rule-row">
              <div className="segmented" role="radiogroup" aria-labelledby="otr-unit">
                {(['HALF_DAY', 'FULL_DAY'] as const).map((u) => (
                  <label key={u} className={input.creditUnit === u ? 'is-on' : undefined}>
                    <input type="radio" name="otr-unit" checked={input.creditUnit === u} onChange={() => set('creditUnit', u)} />
                    {u === 'HALF_DAY' ? t('otRules.aHalfDay') : t('otRules.aFullDay')}
                  </label>
                ))}
              </div>
              <label className="field ot-rule-hours">
                {t('otRules.forEvery')}
                <input type="number" inputMode="decimal" min={0.25} max={24} step={0.25} value={Number.isFinite(input.hoursPerUnit) ? input.hoursPerUnit : ''}
                  onChange={(e) => set('hoursPerUnit', e.target.value === '' ? NaN : Number(e.target.value))} aria-invalid={!hoursOk || undefined} required />
              </label>
              <span className="hint">{t('otRules.hoursOfOvertime')}</span>
            </div>

            <span className="wf-label" id="otr-conv">{t('otRules.shortHours')}</span>
            <div className="ot-choices" role="radiogroup" aria-labelledby="otr-conv">
              {(['BANKED', 'PER_REQUEST'] as const).map((c) => (
                <label key={c} className={input.conversion === c ? 'ot-choice is-on' : 'ot-choice'}>
                  <input type="radio" name="otr-conv" checked={input.conversion === c} onChange={() => set('conversion', c)} />
                  <span>
                    <strong>{t(`otRules.conv.${c}`)}</strong>
                    <span className="hint">{t(`otRules.convHint.${c}`, { hours: num(input.hoursPerUnit || 4), unit: unitWord })}</span>
                  </span>
                </label>
              ))}
            </div>

            <label className="check-line ot-check">
              <input type="checkbox" checked={input.applyRate} onChange={(e) => set('applyRate', e.target.checked)} />
              <span>
                {t('otRules.applyRate')}
                <span className="hint"> {t('otRules.applyRateHint')}</span>
              </span>
            </label>

            <div className="ot-rule-row">
              <label className="field">
                {t('otRules.expiry')}
                <select value={input.expiry} onChange={(e) => set('expiry', e.target.value as OvertimeRuleInput['expiry'])}>
                  {(['NONE', 'DAYS', 'YEAR_END'] as const).map((x) => <option key={x} value={x}>{t(`otRules.expiries.${x}`)}</option>)}
                </select>
              </label>
              {input.expiry === 'DAYS' && (
                <label className="field ot-rule-hours">
                  {t('otRules.days')}
                  <input type="number" inputMode="numeric" min={1} max={730} step={1} value={input.expiryDays ?? ''}
                    onChange={(e) => set('expiryDays', e.target.value === '' ? null : Number(e.target.value))} required />
                </label>
              )}
            </div>
            <p className="hint">{t('otRules.expiryHint')}</p>
          </>
        )}

        {ratesUsed && (
          <fieldset className="ot-rates">
            <legend>{t('otRules.rates')}</legend>
            <div className="ot-rule-row">
              {([['rateWorking', 'WORKING'], ['rateRest', 'REST'], ['rateHoliday', 'HOLIDAY']] as const).map(([key, kind]) => (
                <label key={key} className="field ot-rule-hours">
                  {t(`overtime.dayKind.${kind}`)}
                  <span className="ot-rate-input">
                    <input type="number" inputMode="decimal" min={1} max={5} step={0.25}
                      value={Number.isFinite(input[key]) ? input[key] : ''} aria-invalid={!okRate(input[key]) || undefined}
                      onChange={(e) => set(key, e.target.value === '' ? NaN : Number(e.target.value))} required />
                    <span aria-hidden="true">×</span>
                  </span>
                </label>
              ))}
            </div>
            <p className={ratesOk ? 'hint' : 'form-error'} role={ratesOk ? undefined : 'alert'}>{t('otRules.ratesHint')}</p>
            <span className="wf-label" id="otr-rate-mode">{t('otRules.rateMode')}</span>
            <div className="ot-choices" role="radiogroup" aria-labelledby="otr-rate-mode">
              {(['AUTO', 'EMPLOYEE'] as const).map((m) => (
                <label key={m} className={input.rateMode === m ? 'ot-choice is-on' : 'ot-choice'}>
                  <input type="radio" name="otr-rate-mode" checked={input.rateMode === m} onChange={() => set('rateMode', m)} />
                  <span>
                    <strong>{t(`otRules.rateModes.${m}`)}</strong>
                    <span className="hint">{t(`otRules.rateModeHints.${m}`)}</span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
        )}

        <p className="notice" role="status"><strong>{t('otRules.inShort')}</strong> {ruleText(t, asRule(input, settings.leaveTypes))}</p>

        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={!valid || save.isPending}>{t('common.save')}</button>
          {rule?.id && (
            <button type="button" className="btn reject" disabled={remove.isPending} onClick={() => remove.mutate()}>{t('otRules.remove')}</button>
          )}
        </div>
        {rule?.id && <p className="hint">{t(rule.scope === 'COMPANY' ? 'otRules.removeCompanyHint' : 'otRules.removeHint')}</p>}
      </form>
    </Panel>
  );
}
