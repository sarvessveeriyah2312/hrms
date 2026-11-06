import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { ClaimKind, ClaimType, ClaimTypeInput } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { useOpenParam } from '../../lib/openParam';
import { toTone } from '../../lib/dates';
import { Glyph, type Tone } from '../../components/Glyph';
import { Icon } from '../../components/Icon';
import { Card, EmptyState, List, PageHeader, Row, Status } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { chainText } from './chain';
import { TypeWorkflows } from '../workflows/TypeWorkflows';
import { approvalText, useSubjectFlows } from '../workflows/typeFlows';

const TONES: Tone[] = ['orange', 'blue', 'teal', 'green', 'purple', 'pink'];

/** Limits are kept as plain numbers: each person claims in their own entity's currency. */
const num = (n: number | null) => (n === null ? '' : String(Number(n)));

/** Claim types: limits, mileage rate, receipts and approval. Needs "Manage claim policies". */
export function ClaimSettingsPage() {
  const { t } = useTranslation();
  const types = useQuery({ queryKey: ['claims', 'settings', 'types'], queryFn: api.claims.settings.types });
  const flows = useSubjectFlows('CLAIM');
  const [editingState, setEditingState] = useState<ClaimType | 'new' | null>(null);
  // Opened from search: ?type=<claim type id>
  const [typeParam, clearType] = useOpenParam('type');
  const linked = typeParam ? types.data?.find((ct) => ct.id === typeParam) : undefined;
  const editing = editingState ?? linked ?? null;
  const setEditing = (e: ClaimType | 'new' | null) => {
    setEditingState(e);
    clearType();
  };

  return (
    <>
      <PageHeader
        title={t('nav.claimSettings')}
        subtitle={t('cs.subtitle')}
        actions={<button type="button" className="btn primary" onClick={() => setEditing('new')}><Icon name="plus" size={16} />{t('cs.newType')}</button>}
      />
      {types.isError && <p className="form-error" role="alert">{errorText(types.error)}</p>}
      <Card title={t('cs.types')} count={types.data?.length}>
        {types.data && types.data.length === 0 ? (
          <EmptyState icon="receipt" tone="orange" title={t('cs.noneTitle')} action={<button type="button" className="btn" onClick={() => setEditing('new')}>{t('cs.newType')}</button>}>
            {t('cs.noneBody')}
          </EmptyState>
        ) : (
          <List>
            {types.data?.map((ct) => (
              <Row
                key={ct.id}
                lead={<Glyph icon={ct.kind === 'MILEAGE' ? 'pin' : 'receipt'} tone={toTone(ct.tone, 'orange')} />}
                title={ct.name}
                meta={[
                  ct.kind === 'MILEAGE' ? t('cs.ratePerKm', { rate: num(ct.ratePerKm) }) : ct.receiptRequired ? t('cs.receiptNeeded') : t('cs.receiptOptional'),
                  ct.maxPerClaim !== null ? t('cs.perClaimShort', { n: num(ct.maxPerClaim) }) : null,
                  ct.maxPerMonth !== null ? t('cs.perMonthShort', { n: num(ct.maxPerMonth) }) : null,
                  ct.maxPerClaim === null && ct.maxPerMonth === null ? t('cs.noLimit') : null,
                  approvalText(t, flows.data, ct.id, chainText(ct)),
                ].filter(Boolean).join(' · ')}
                trail={
                  <>
                    {!ct.active && <Status tone="grey">{t('ls.retired')}</Status>}
                    <button type="button" className="btn small" onClick={() => setEditing(ct)}>{t('common.edit')}</button>
                  </>
                }
              />
            ))}
          </List>
        )}
      </Card>
      <p className="hint card-pad">{t('cs.currencyNote')}</p>

      {editing && <TypeEditor type={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function TypeEditor({ type, onClose }: { type: ClaimType | null; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [name, setName] = useState(type?.name ?? '');
  const [tone, setTone] = useState(type?.tone ?? 'orange');
  const [kind, setKind] = useState<ClaimKind>(type?.kind ?? 'RECEIPT');
  const [perClaim, setPerClaim] = useState(num(type?.maxPerClaim ?? null));
  const [perMonth, setPerMonth] = useState(num(type?.maxPerMonth ?? null));
  const [rate, setRate] = useState(num(type?.ratePerKm ?? null));
  const [receipt, setReceipt] = useState(type?.receiptRequired ?? true);
  const [levels, setLevels] = useState(type?.approvalLevels ?? 1);
  const [hr, setHr] = useState(type?.hrApproval ?? false);
  const [active, setActive] = useState(type?.active ?? true);

  const save = useMutation({
    mutationFn: () => {
      const input: ClaimTypeInput = {
        name: name.trim(), tone, kind,
        maxPerClaim: perClaim ? Number(perClaim) : null,
        maxPerMonth: perMonth ? Number(perMonth) : null,
        ratePerKm: kind === 'MILEAGE' ? Number(rate) : null,
        receiptRequired: kind === 'RECEIPT' && receipt,
        approvalLevels: levels, hrApproval: hr, active,
      };
      return type ? api.claims.settings.updateType(type.id, input) : api.claims.settings.createType(input);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['claims'] });
      onClose();
    },
  });

  return (
    <Panel title={type ? t('ls.editType', { name: type.name }) : t('cs.newType')} onClose={onClose}>
      <form
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        {save.error && <p className="form-error" role="alert">{errorText(save.error)}</p>}
        <label className="field">{t('ls.name')}<input value={name} onChange={(e) => setName(e.target.value)} required maxLength={60} placeholder={t('cs.nameExample')} /></label>

        <fieldset>
          <legend>{t('ls.colour')}</legend>
          <div className="tone-picker" role="radiogroup" aria-label={t('ls.colour')}>
            {TONES.map((x) => (
              <label key={x} className={tone === x ? 'is-on' : undefined}>
                <input type="radio" name="tone" value={x} checked={tone === x} onChange={() => setTone(x)} aria-label={t(`ls.tones.${x}`)} />
                <Glyph icon={kind === 'MILEAGE' ? 'pin' : 'receipt'} tone={x} />
              </label>
            ))}
          </div>
        </fieldset>

        <fieldset>
          <legend>{t('cs.howWorkedOut')}</legend>
          <div className="segmented" role="radiogroup" aria-label={t('cs.howWorkedOut')}>
            {(['RECEIPT', 'MILEAGE'] as ClaimKind[]).map((k) => (
              <label key={k} className={kind === k ? 'is-on' : undefined}>
                <input type="radio" name="kind" value={k} checked={kind === k} onChange={() => setKind(k)} />
                {k === 'RECEIPT' ? t('cs.kindReceipt') : t('cs.kindMileage')}
              </label>
            ))}
          </div>
          <p className="hint">{kind === 'RECEIPT' ? t('cs.kindReceiptHint') : t('cs.kindMileageHint')}</p>
          {kind === 'MILEAGE' ? (
            <label className="field">
              {t('cs.rate')}
              <input type="number" inputMode="decimal" min={0.01} max={100} step={0.01} value={rate} onChange={(e) => setRate(e.target.value)} required />
            </label>
          ) : (
            <label className="check-line"><input type="checkbox" checked={receipt} onChange={(e) => setReceipt(e.target.checked)} />{t('cs.needsReceipt')}</label>
          )}
        </fieldset>

        <fieldset>
          <legend>{t('cs.limits')}</legend>
          <div className="field-row">
            <label className="field">{t('cs.perClaim')}<input type="number" inputMode="decimal" min={0} step={0.01} value={perClaim} onChange={(e) => setPerClaim(e.target.value)} placeholder={t('cs.noLimit')} /></label>
            <label className="field">{t('cs.perMonth')}<input type="number" inputMode="decimal" min={0} step={0.01} value={perMonth} onChange={(e) => setPerMonth(e.target.value)} placeholder={t('cs.noLimit')} /></label>
          </div>
          <p className="hint">{t('cs.limitsHint')}</p>
        </fieldset>

        <fieldset>
          <legend>{t('ls.approvalTitle')}</legend>
          <TypeWorkflows subject="CLAIM" typeId={type?.id ?? null}>
            <label className="field">
              {t('ls.managerLevels')}
              <select value={levels} onChange={(e) => setLevels(Number(e.target.value))}>
                <option value={0}>{t('ls.levels0')}</option>
                <option value={1}>{t('ls.levels1')}</option>
                <option value={2}>{t('ls.levels2')}</option>
              </select>
            </label>
            <label className="check-line"><input type="checkbox" checked={hr} onChange={(e) => setHr(e.target.checked)} />{t('ls.hrLast')}</label>
            <p className="hint">{t('ls.chainHint', { chain: chainText({ approvalLevels: levels, hrApproval: hr }) })}</p>
          </TypeWorkflows>
        </fieldset>

        {type && (
          <label className="check-line"><input type="checkbox" checked={!active} onChange={(e) => setActive(!e.target.checked)} />{t('cs.retire')}</label>
        )}

        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={save.isPending || !name.trim()}>
            {save.isPending ? t('common.saving') : type ? t('common.saveChanges') : t('cs.create')}
          </button>
        </div>
      </form>
    </Panel>
  );
}
