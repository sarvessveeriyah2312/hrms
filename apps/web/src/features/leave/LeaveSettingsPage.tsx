import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { LeaveType, LeaveTypeInput } from '@hrms/api-client';
import i18n from '../../i18n';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { useOpenParam } from '../../lib/openParam';
import { monthName, toTone } from '../../lib/dates';
import { Glyph, type Tone } from '../../components/Glyph';
import { Icon } from '../../components/Icon';
import { Card, List, PageHeader, Row, Status, Tabs } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { EntitlementsTab } from './EntitlementsTab';
import { HolidayYearList } from './HolidayYearList';
import { HolidayCalendar } from './HolidayCalendar';
import { TypeWorkflows } from '../workflows/TypeWorkflows';
import { approvalText, useSubjectFlows } from '../workflows/typeFlows';

const TONES: Tone[] = ['teal', 'blue', 'green', 'orange', 'purple', 'pink'];

function chainText(lt: Pick<LeaveType, 'approvalLevels' | 'hrApproval'>) {
  const t = i18n.t;
  const parts = lt.approvalLevels === 0 ? [] : lt.approvalLevels === 1 ? [t('ls.chainManager')] : [t('ls.chainManager'), t('ls.chainTheirManager')];
  if (lt.hrApproval || parts.length === 0) parts.push(t('ls.chainHr'));
  return parts.join(' → ');
}

const TABS = ['types', 'holidays', 'entitlements'] as const;
type Tab = (typeof TABS)[number];

/** Leave settings in three tabs: leave types, holidays and working week, and per-person entitlements. Needs "Manage leave policies". */
export function LeaveSettingsPage() {
  const { t } = useTranslation();
  const types = useQuery({ queryKey: ['leave', 'settings', 'types'], queryFn: api.leave.settings.types });
  const flows = useSubjectFlows('LEAVE');
  const holidays = useQuery({ queryKey: ['leave', 'settings', 'holidays'], queryFn: api.leave.settings.holidays });
  const [editingState, setEditingState] = useState<LeaveType | 'new' | null>(null);
  // Opened from search: ?type=<leave type id>
  const [typeParam, clearType] = useOpenParam('type');
  const linked = typeParam ? types.data?.find((lt) => lt.id === typeParam) : undefined;
  const editing = editingState ?? linked ?? null;
  const setEditing = (e: LeaveType | 'new' | null) => {
    setEditingState(e);
    clearType();
  };
  const [params, setParams] = useSearchParams();
  const tab: Tab = (TABS as readonly string[]).includes(params.get('tab') ?? '') ? (params.get('tab') as Tab) : 'types';
  const [thisYear] = useState(() => String(new Date().getFullYear()));
  const holidaysThisYear = (holidays.data ?? []).filter((h) => h.date.startsWith(thisYear)).length;

  return (
    <>
      <PageHeader
        title={t('nav.leaveSettings')}
        subtitle={t(`ls.subtitle.${tab}`)}
        actions={tab === 'types' && <button type="button" className="btn primary" onClick={() => setEditing('new')}><Icon name="plus" size={16} />{t('ls.newType')}</button>}
      />
      {(types.isError || holidays.isError) && <p className="form-error" role="alert">{errorText(types.error ?? holidays.error)}</p>}

      <Tabs titled
        label={t('nav.leaveSettings')}
        value={tab}
        onChange={(key) => setParams(key === 'types' ? {} : { tab: key }, { replace: true })}
        tabs={[
          { key: 'types', label: t('ls.tabs.types'), icon: 'calendar', count: types.data?.length },
          { key: 'holidays', label: t('ls.tabs.holidays'), icon: 'star', count: holidaysThisYear },
          { key: 'entitlements', label: t('ls.tabs.entitlements'), icon: 'users' },
        ]}
      >
        {tab === 'types' && (
          <Card title={t('ls.tabs.types')} count={types.data?.length}>
            <List>
              {types.data?.map((lt) => (
                <Row
                  key={lt.id}
                  lead={<Glyph icon="calendar" tone={toTone(lt.tone)} />}
                  title={lt.name}
                  meta={[
                    lt.trackBalance ? t('ls.daysYear', { n: Number(lt.defaultDays) }) : t('leave.noLimit'),
                    ...lt.serviceTiers.map((x) => t('ls.tierShort', { days: Number(x.days), years: x.minYears })),
                    lt.trackBalance && lt.proRate ? t('ls.proRated') : null,
                    lt.trackBalance && Number(lt.carryMax) > 0
                      ? lt.carryExpiryMonth
                        ? t('ls.carryUseBy', { n: Number(lt.carryMax), month: monthName(lt.carryExpiryMonth) })
                        : t('ls.carryUpTo', { n: Number(lt.carryMax) })
                      : null,
                    lt.requiresAttachment ? t('ls.docRequired') : null,
                    approvalText(t, flows.data, lt.id, chainText(lt)),
                    lt.halfDayAllowed ? t('ls.halfDays') : t('ls.fullDays'),
                    lt.paid ? t('ls.paid') : t('ls.unpaid'),
                  ].filter(Boolean).join(' · ')}
                  trail={
                    <>
                      {!lt.active && <Status tone="grey">{t('ls.retired')}</Status>}
                      <button type="button" className="btn small" onClick={() => setEditing(lt)}>{t('common.edit')}</button>
                    </>
                  }
                />
              ))}
            </List>
          </Card>
        )}

        {tab === 'holidays' && (
          <div className="hol-layout">
            <HolidayCalendar holidays={holidays.data ?? []} />
            <HolidayYearList holidays={holidays.data ?? []} />
          </div>
        )}

        {tab === 'entitlements' && <EntitlementsTab types={types.data ?? []} />}
      </Tabs>

      {editing && <TypeEditor type={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function TypeEditor({ type, onClose }: { type: LeaveType | null; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [input, setInput] = useState<LeaveTypeInput>({
    name: type?.name ?? '',
    tone: type?.tone ?? 'teal',
    paid: type?.paid ?? true,
    trackBalance: type?.trackBalance ?? true,
    defaultDays: type ? Number(type.defaultDays) : 0,
    halfDayAllowed: type?.halfDayAllowed ?? true,
    approvalLevels: type?.approvalLevels ?? 1,
    hrApproval: type?.hrApproval ?? false,
    active: type?.active ?? true,
    proRate: type?.proRate ?? false,
    carryMax: type ? Number(type.carryMax) : 0,
    serviceTiers: type?.serviceTiers.map((x) => ({ minYears: x.minYears, days: Number(x.days) })) ?? [],
    carryExpiryMonth: type?.carryExpiryMonth ?? 0,
    requiresAttachment: type?.requiresAttachment ?? false,
  });
  const tiers = input.serviceTiers ?? [];
  const setTier = (i: number, key: 'minYears' | 'days', value: number) =>
    set('serviceTiers', tiers.map((tier, j) => (j === i ? { ...tier, [key]: value } : tier)));
  const set = <K extends keyof LeaveTypeInput>(k: K, v: LeaveTypeInput[K]) => setInput((x) => ({ ...x, [k]: v }));
  const save = useMutation({
    mutationFn: () => (type ? api.leave.settings.updateType(type.id, input) : api.leave.settings.createType(input)),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['leave'] });
      onClose();
    },
  });

  return (
    <Panel title={type ? t('ls.editType', { name: type.name }) : t('ls.newType')} onClose={onClose}>
      <form
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        {save.error && <p className="form-error" role="alert">{errorText(save.error)}</p>}
        <label className="field">{t('ls.name')}<input value={input.name} onChange={(e) => set('name', e.target.value)} required maxLength={60} /></label>

        <fieldset>
          <legend>{t('ls.colour')}</legend>
          <div className="tone-picker" role="radiogroup" aria-label={t('ls.colour')}>
            {TONES.map((tone) => (
              <label key={tone} className={input.tone === tone ? 'is-on' : undefined}>
                <input type="radio" name="tone" value={tone} checked={input.tone === tone} onChange={() => set('tone', tone)} aria-label={t(`ls.tones.${tone}`)} />
                <Glyph icon="calendar" tone={tone} />
              </label>
            ))}
          </div>
        </fieldset>

        <fieldset>
          <legend>{t('ls.entitlement')}</legend>
          <label className="check-line"><input type="checkbox" checked={!!input.trackBalance} onChange={(e) => set('trackBalance', e.target.checked)} />{t('ls.hasAllowance')}</label>
          {input.trackBalance && (
            <label className="field">{t('ls.daysAYear')}<input type="number" min={0} max={365} step={0.5} value={input.defaultDays ?? 0} onChange={(e) => set('defaultDays', Number(e.target.value))} /></label>
          )}
          <label className="check-line"><input type="checkbox" checked={!!input.halfDayAllowed} onChange={(e) => set('halfDayAllowed', e.target.checked)} />{t('ls.canHalf')}</label>
          <label className="check-line"><input type="checkbox" checked={!!input.paid} onChange={(e) => set('paid', e.target.checked)} />{t('ls.paidLeave')}</label>
          <label className="check-line"><input type="checkbox" checked={!!input.requiresAttachment} onChange={(e) => set('requiresAttachment', e.target.checked)} />{t('ls.needsDoc')}</label>
        </fieldset>

        {input.trackBalance && (
          <fieldset>
            <legend>{t('ls.autoRules')}</legend>
            <label className="check-line">
              <input type="checkbox" checked={!!input.proRate} onChange={(e) => set('proRate', e.target.checked)} />
              {t('ls.proRate')}
            </label>
            <p className="hint">{t('ls.proRateHint')}</p>

            <div className="tier-list">
              <span className="field">{t('ls.tiers')}</span>
              {tiers.length === 0 && <p className="hint">{t('ls.everyoneGets', { n: Number(input.defaultDays ?? 0) })}</p>}
              {tiers.map((tier, i) => (
                <div key={i} className="tier-row">
                  <span>{t('ls.tierAfter')}</span>
                  <input className="input" type="number" min={1} max={50} value={tier.minYears} onChange={(e) => setTier(i, 'minYears', Number(e.target.value))} aria-label={t('ls.yearsOfService')} />
                  <span>{t('ls.tierYears')}</span>
                  <input className="input" type="number" min={0} max={365} step={0.5} value={tier.days} onChange={(e) => setTier(i, 'days', Number(e.target.value))} aria-label={t('ls.daysAYear')} />
                  <span>{t('ls.tierDays')}</span>
                  <button type="button" className="btn small" onClick={() => set('serviceTiers', tiers.filter((_, j) => j !== i))} aria-label={t('ls.removeTier', { n: tier.minYears })}>{t('common.remove')}</button>
                </div>
              ))}
              <button
                type="button"
                className="link-button"
                onClick={() => {
                  const last = tiers[tiers.length - 1];
                  set('serviceTiers', [...tiers, { minYears: last ? last.minYears + 3 : 2, days: last ? last.days + 2 : Number(input.defaultDays ?? 0) + 2 }]);
                }}
              >
                {t('ls.addTier')}
              </button>
              <p className="hint">{t('ls.tierHint')}</p>
            </div>

            <label className="field">
              {t('ls.carryMax')}
              <input type="number" min={0} max={365} step={0.5} value={input.carryMax ?? 0} onChange={(e) => set('carryMax', Number(e.target.value))} />
              <span className="hint">{t('ls.carryMaxHint')}</span>
            </label>
            {Number(input.carryMax ?? 0) > 0 && (
              <label className="field">
                {t('ls.carryExpiry')}
                <select value={input.carryExpiryMonth ?? 0} onChange={(e) => set('carryExpiryMonth', Number(e.target.value))}>
                  <option value={0}>{t('ls.noDeadline')}</option>
                  {Array.from({ length: 12 }, (_, i) => (
                    <option key={i + 1} value={i + 1}>{monthName(i + 1)}</option>
                  ))}
                </select>
                <span className="hint">{t('ls.carryExpiryHint')}</span>
              </label>
            )}
          </fieldset>
        )}

        <fieldset>
          <legend>{t('ls.approvalTitle')}</legend>
          <TypeWorkflows subject="LEAVE" typeId={type?.id ?? null}>
            <label className="field">
              {t('ls.managerLevels')}
              <select value={input.approvalLevels} onChange={(e) => set('approvalLevels', Number(e.target.value))}>
                <option value={0}>{t('ls.levels0')}</option>
                <option value={1}>{t('ls.levels1')}</option>
                <option value={2}>{t('ls.levels2')}</option>
              </select>
            </label>
            <label className="check-line"><input type="checkbox" checked={!!input.hrApproval} onChange={(e) => set('hrApproval', e.target.checked)} />{t('ls.hrLast')}</label>
            <p className="hint">{t('ls.chainHint', { chain: chainText({ approvalLevels: input.approvalLevels ?? 1, hrApproval: !!input.hrApproval }) })}</p>
          </TypeWorkflows>
        </fieldset>

        {type && (
          <label className="check-line"><input type="checkbox" checked={!input.active} onChange={(e) => set('active', !e.target.checked)} />{t('ls.retire')}</label>
        )}

        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={save.isPending}>{save.isPending ? t('common.saving') : type ? t('common.saveChanges') : t('ls.create')}</button>
        </div>
      </form>
    </Panel>
  );
}
