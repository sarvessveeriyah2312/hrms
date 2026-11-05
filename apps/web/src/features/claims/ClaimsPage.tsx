import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { Claim, ClaimFilter, ClaimType, FileMeta } from '@hrms/api-client';
import { api } from '../../lib/api';
import { can, errorText, SCOPE_LABEL, useAccess } from '../../lib/access';
import { addDays, formatDate, isoDate, relative, toTone } from '../../lib/dates';
import { LEAVE_STATUS as STATUS } from '../../lib/leaveStatus';
import { formatMoney, formatMonth, monthKey } from '../../lib/money';
import { saveBlob } from '../../lib/download';
import { Glyph } from '../../components/Glyph';
import { Icon } from '../../components/Icon';
import { Avatar, Card, EmptyState, List, PageHeader, Row, StatCard, Status, Tabs } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Pager } from '../../components/app/Pager';
import { usePaging } from '../../lib/paging';
import { AttachmentButton, FileField } from '../../components/app/files';
import { Timeline } from '../approvals/Timeline';
import { chainText } from './chain';
import './claims.css';

const TABS = ['mine', 'team', 'pay'] as const;
type Tab = (typeof TABS)[number];
type Editor = { mode: 'new' } | { mode: 'edit'; claim: Claim };

const DAY = (iso: string) => formatDate(iso, { day: 'numeric', month: 'short' });
const sum = (claims: Claim[]) => claims.reduce((n, c) => n + Number(c.amount), 0);

/** Your claims and monthly limits; claims of the people your claims:view scope covers; and (HR) paying approved claims. */
export function ClaimsPage() {
  const { t } = useTranslation();
  const { user } = useAccess();
  const [params, setParams] = useSearchParams();
  const [editorState, setEditorState] = useState<Editor | null>(null);
  // Opened from "New → Claim" in the top bar: /app/claims?new=1
  const editor: Editor | null = editorState ?? (params.get('new') === '1' ? { mode: 'new' } : null);
  const [picked, setPicked] = useState<string | null>(null);
  // Opened from a notification: /app/claims?claim=<id>
  const viewing = params.get('claim') ?? picked;
  const setViewing = (id: string | null) => {
    setPicked(id);
    if (params.get('claim')) setParams({}, { replace: true });
  };
  const setEditor = (e: Editor | null) => {
    setEditorState(e);
    if (!e && params.get('new')) setParams({}, { replace: true });
  };
  const canSubmit = can(user, 'claims:submit') && !!user?.employeeId;
  const seesOthers = can(user, 'claims:view', 'TEAM');
  const canPay = can(user, 'claims:manage');
  const requested = params.get('tab') as Tab | null;
  const tab: Tab = requested && TABS.includes(requested) && (requested !== 'team' || seesOthers) && (requested !== 'pay' || canPay)
    ? requested : canSubmit ? 'mine' : seesOthers ? 'team' : 'mine';
  const setTab = (key: string) => setParams(key === 'mine' ? {} : { tab: key }, { replace: true });

  const mine = useQuery({ queryKey: ['claims', 'mine'], queryFn: () => api.claims.mine(), enabled: !!user?.employeeId });
  const usage = useQuery({ queryKey: ['claims', 'usage', 'now'], queryFn: () => api.claims.usage(), enabled: canSubmit });
  const unpaid = useQuery({ queryKey: ['claims', 'team', 'UNPAID'], queryFn: () => api.claims.team({ status: 'UNPAID', from: addDays(isoDate(new Date()), -400) }), enabled: canPay });
  const returned = (mine.data ?? []).filter((c) => c.status === 'RETURNED');

  return (
    <>
      <PageHeader
        title={t('nav.claims')}
        subtitle={t(`claims.subtitle.${tab}`)}
        actions={canSubmit && <button type="button" className="btn primary" onClick={() => setEditor({ mode: 'new' })}><Icon name="plus" size={16} />{t('claims.new')}</button>}
      />
      {(mine.isError || usage.isError) && <p className="form-error" role="alert">{errorText(mine.error ?? usage.error)}</p>}

      {returned.map((c) => (
        <div key={c.id} className="notice notice--action" role="status">
          <span><strong>{c.typeName}, {formatMoney(c.amount, c.currency)}</strong> {t('claims.sentBack', { comment: c.returnedComment })}</span>
          <span className="panel-actions">
            <button type="button" className="btn small primary" onClick={() => setEditor({ mode: 'edit', claim: c })}>{t('claims.editResubmit')}</button>
            <button type="button" className="btn small" onClick={() => setViewing(c.id)}>{t('common.view')}</button>
          </span>
        </div>
      ))}

      <Tabs titled
        label={t('nav.claims')}
        value={tab}
        onChange={setTab}
        tabs={[
          ...(user?.employeeId ? [{ key: 'mine', label: t('claims.tabs.mine'), icon: 'receipt' as const }] : []),
          ...(seesOthers ? [{ key: 'team', label: t('claims.tabs.team'), icon: 'users' as const }] : []),
          ...(canPay ? [{ key: 'pay', label: t('claims.tabs.pay'), count: unpaid.data?.claims.length || undefined }] : []),
        ]}
      >
        {tab === 'mine' && (
          <MineTab claims={mine.data} usage={usage.data} canSubmit={canSubmit} onNew={() => setEditor({ mode: 'new' })} onOpen={setViewing} />
        )}
        {tab === 'team' && <TeamTab onOpen={setViewing} />}
        {tab === 'pay' && <PayTab claims={unpaid.data?.claims} onOpen={setViewing} />}
      </Tabs>

      {editor && <ClaimForm editing={editor.mode === 'edit' ? editor.claim : null} onClose={() => setEditor(null)} />}
      {viewing && (
        <ClaimPanel
          id={viewing}
          meId={user?.employeeId ?? null}
          onEdit={(c) => { setViewing(null); setEditor({ mode: 'edit', claim: c }); }}
          onClose={() => setViewing(null)}
        />
      )}
    </>
  );
}

// ---------- your claims ----------

function MineTab({ claims, usage, canSubmit, onNew, onOpen }: {
  claims: Claim[] | undefined;
  usage: { currency: string; month: string; types: { claimTypeId: string; name: string; tone: string; maxPerMonth: number | null; approved: number; pending: number; left: number | null }[] } | undefined;
  canSubmit: boolean;
  onNew: () => void;
  onOpen: (id: string) => void;
}) {
  const { t } = useTranslation();
  const [thisMonth] = useState(() => monthKey(isoDate(new Date())));
  const list = claims ?? [];
  const currency = usage?.currency ?? list[0]?.currency ?? 'MYR';
  const month = list.filter((c) => monthKey(c.expenseDate) === thisMonth && (c.status === 'APPROVED' || c.status === 'PENDING'));
  const waiting = list.filter((c) => c.status === 'PENDING');
  const toBePaid = list.filter((c) => c.status === 'APPROVED' && !c.paidAt);
  const limited = (usage?.types ?? []).filter((u) => u.maxPerMonth !== null);

  return (
    <>
      <div className="grid grid--stats">
        <StatCard icon="receipt" label={t('claims.stats.thisMonth')} value={formatMoney(sum(month), currency)} note={t('claims.stats.thisMonthNote', { month: formatMonth(thisMonth) })} />
        <StatCard icon="clock" label={t('claims.stats.waiting')} value={formatMoney(sum(waiting), currency)} note={t('claims.stats.nClaims', { count: waiting.length })} />
        <StatCard icon="check" label={t('claims.stats.toBePaid')} value={formatMoney(sum(toBePaid), currency)} note={t('claims.stats.toBePaidNote', { count: toBePaid.length })} />
      </div>

      <div className="grid grid--main">
        <Card title={t('claims.yourClaims')} count={claims?.length}>
          {claims && claims.length === 0 ? (
            <EmptyState icon="receipt" tone="orange" title={t('claims.noneYet')} action={canSubmit && <button type="button" className="btn" onClick={onNew}>{t('claims.new')}</button>}>
              {t('claims.noneYetBody')}
            </EmptyState>
          ) : (
            <List>
              {list.map((c) => (
                <Row
                  key={c.id}
                  lead={<Glyph icon={c.kind === 'MILEAGE' ? 'pin' : 'receipt'} tone={toTone(c.tone, 'orange')} />}
                  title={`${c.typeName}, ${formatMoney(c.amount, c.currency)}`}
                  meta={[
                    c.description,
                    DAY(c.expenseDate),
                    c.pendingWith ? t('claims.withApprover', { name: c.pendingWith.approver, step: c.pendingWith.label })
                      : c.status === 'RETURNED' ? t('claims.changeResubmit')
                        : c.paidAt ? t('claims.paidOn', { when: relative(c.paidAt) })
                          : c.decidedAt ? t('claims.decided', { when: relative(c.decidedAt) }) : null,
                  ].filter(Boolean).join(' · ')}
                  trail={
                    <>
                      <ClaimStatus claim={c} />
                      <button type="button" className="btn small" onClick={() => onOpen(c.id)}>{t('common.view')}</button>
                    </>
                  }
                />
              ))}
            </List>
          )}
        </Card>

        <div className="col">
          <Card title={t('claims.limitsTitle', { month: formatMonth(usage?.month ?? thisMonth) })}>
            {usage && limited.length === 0 ? (
              <p className="muted card-pad">{t('claims.noLimits')}</p>
            ) : (
              <ul className="claim-limits">
                {limited.map((u) => {
                  const used = Number(u.approved) + Number(u.pending);
                  const max = Number(u.maxPerMonth);
                  const pct = Math.min(100, Math.round((used / max) * 100));
                  return (
                    <li key={u.claimTypeId}>
                      <div className="claim-limits__head">
                        <span>{u.name}</span>
                        <span className="muted">{t('claims.leftOf', { left: formatMoney(u.left, currency), max: formatMoney(max, currency) })}</span>
                      </div>
                      <div className={pct >= 100 ? 'claim-bar is-full' : 'claim-bar'} role="meter" aria-valuemin={0} aria-valuemax={max} aria-valuenow={used}
                        aria-label={t('claims.usedOf', { used: formatMoney(used, currency), max: formatMoney(max, currency), name: u.name })}>
                        <span style={{ width: `${pct}%` }} />
                      </div>
                      {Number(u.pending) > 0 && <span className="hint">{t('claims.pendingIncluded', { amount: formatMoney(u.pending, currency) })}</span>}
                    </li>
                  );
                })}
              </ul>
            )}
          </Card>
        </div>
      </div>
    </>
  );
}

function ClaimStatus({ claim }: { claim: Claim }) {
  const { t } = useTranslation();
  if (claim.paidAt) return <Status tone="green">{t('claims.paid')}</Status>;
  return <Status tone={STATUS[claim.status].tone}>{STATUS[claim.status].label}</Status>;
}

// ---------- the team's claims ----------

type TeamFilter = Extract<ClaimFilter, 'PENDING' | 'UNPAID' | 'PAID'> | 'ALL';
const FILTERS: TeamFilter[] = ['PENDING', 'UNPAID', 'PAID', 'ALL'];

function TeamTab({ onOpen }: { onOpen: (id: string) => void }) {
  const { t } = useTranslation();
  const [filter, setFilter] = useState<TeamFilter>('PENDING');
  const [from] = useState(() => addDays(isoDate(new Date()), -90));
  const team = useQuery({
    queryKey: ['claims', 'team', filter, from],
    queryFn: () => api.claims.team({ status: filter === 'ALL' ? undefined : filter, from }),
  });
  const claims = team.data?.claims ?? [];
  const byCurrency = totals(claims);
  const paging = usePaging(claims, { resetKey: filter });

  return (
    <Card
      title={t('claims.teamTitle')}
      count={team.data?.claims.length}
      action={team.data && <Status tone="blue">{SCOPE_LABEL[team.data.scope]}</Status>}
    >
      <div className="claims-toolbar">
        <div className="segmented" role="radiogroup" aria-label={t('claims.filterLabel')}>
          {FILTERS.map((f) => (
            <label key={f} className={filter === f ? 'is-on' : undefined}>
              <input type="radio" name="claim-filter" checked={filter === f} onChange={() => setFilter(f)} />
              {t(`claims.filter.${f}`)}
            </label>
          ))}
        </div>
        {claims.length > 0 && <span className="muted">{t('claims.total', { amount: byCurrency })}</span>}
      </div>
      {team.isError && <p className="form-error" role="alert">{errorText(team.error)}</p>}
      <List>
        {paging.visible.map((c) => (
          <Row
            key={c.id}
            lead={<Avatar name={c.employeeName} />}
            title={`${c.employeeName} · ${formatMoney(c.amount, c.currency)}`}
            meta={[c.typeName, c.description, DAY(c.expenseDate), c.pendingWith && t('claims.withName', { name: c.pendingWith.approver })].filter(Boolean).join(' · ')}
            onClick={() => onOpen(c.id)}
            trail={<ClaimStatus claim={c} />}
          />
        ))}
        {team.data && claims.length === 0 && <Row title={t('claims.teamEmpty')} meta={t('claims.last90')} />}
      </List>
      <Pager {...paging} label={t('claims.teamTitle')} />
    </Card>
  );
}

/** "RM 120.00" or "RM 120.00 + S$40.00" when people are paid in different currencies. */
function totals(claims: Claim[]) {
  const by = new Map<string, number>();
  claims.forEach((c) => by.set(c.currency, (by.get(c.currency) ?? 0) + Number(c.amount)));
  return [...by].map(([cur, n]) => formatMoney(n, cur)).join(' + ');
}

// ---------- paying approved claims (HR) ----------

function PayTab({ claims, onOpen }: { claims: Claim[] | undefined; onOpen: (id: string) => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [reference, setReference] = useState('');
  const [month, setMonth] = useState(() => monthKey(isoDate(new Date())));
  const [done, setDone] = useState<number | null>(null);
  const list = claims ?? [];
  const picked = list.filter((c) => selected.has(c.id));
  const toggle = (id: string) => setSelected((s) => {
    const next = new Set(s);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });
  const allOn = list.length > 0 && picked.length === list.length;
  const paging = usePaging(list);

  const pay = useMutation({
    mutationFn: () => api.claims.pay(picked.map((c) => c.id), reference.trim() || undefined),
    onSuccess: async (r) => {
      setDone(r.paid);
      setSelected(new Set());
      setReference('');
      await queryClient.invalidateQueries({ queryKey: ['claims'] });
    },
  });
  const exportCsv = useMutation({
    mutationFn: () => api.claims.exportCsv(month),
    onSuccess: (blob) => saveBlob(blob, `claims-${month}.csv`),
  });

  return (
    <div className="grid grid--main">
      <Card title={t('claims.pay.title')} count={claims?.length}>
        {done !== null && <p className="notice card-pad" role="status">{t('claims.pay.done', { count: done })}</p>}
        {pay.error && <p className="form-error" role="alert">{errorText(pay.error)}</p>}
        {claims && list.length === 0 ? (
          <EmptyState icon="check" tone="green" title={t('claims.pay.none')}>{t('claims.pay.noneBody')}</EmptyState>
        ) : (
          <>
            <div className="claims-toolbar">
              <label className="check-line">
                <input type="checkbox" checked={allOn} onChange={() => setSelected(allOn ? new Set() : new Set(list.map((c) => c.id)))} />
                {t('claims.pay.selectAll')}
              </label>
            </div>
            <ul className="ui-list pay-list">
              {paging.visible.map((c) => (
                <li key={c.id} className={selected.has(c.id) ? 'pay-row is-on' : 'pay-row'}>
                  <label className="pay-row__check">
                    <input type="checkbox" checked={selected.has(c.id)} onChange={() => toggle(c.id)}
                      aria-label={t('claims.pay.selectOne', { name: c.employeeName, amount: formatMoney(c.amount, c.currency) })} />
                  </label>
                  <button type="button" className="pay-row__body" onClick={() => onOpen(c.id)}>
                    <span className="ui-row__title">{c.employeeName} · {formatMoney(c.amount, c.currency)}</span>
                    <span className="ui-row__meta">{[c.typeName, c.description, DAY(c.expenseDate), c.decidedAt && t('claims.approvedWhen', { when: relative(c.decidedAt) })].filter(Boolean).join(' · ')}</span>
                  </button>
                </li>
              ))}
            </ul>
            <Pager {...paging} label={t('claims.pay.title')} />
            <form
              className="pay-bar"
              onSubmit={(e) => {
                e.preventDefault();
                setDone(null);
                pay.mutate();
              }}
            >
              <span><strong>{t('claims.pay.selected', { count: picked.length })}</strong>{picked.length > 0 && ` · ${totals(picked)}`}</span>
              <input className="input" value={reference} onChange={(e) => setReference(e.target.value)} maxLength={100}
                placeholder={t('claims.pay.refPlaceholder')} aria-label={t('claims.pay.ref')} />
              <button type="submit" className="btn primary" disabled={picked.length === 0 || pay.isPending}>
                {pay.isPending ? t('common.saving') : t('claims.pay.markPaid')}
              </button>
            </form>
          </>
        )}
      </Card>

      <div className="col">
        <Card title={t('claims.export.title')} padded>
          <div className="stack">
            <p className="hint">{t('claims.export.body')}</p>
            <label className="field">{t('claims.export.month')}<input type="month" value={month} onChange={(e) => setMonth(e.target.value)} required /></label>
            {exportCsv.error && <p className="form-error" role="alert">{errorText(exportCsv.error)}</p>}
            <div className="panel-actions">
              <button type="button" className="btn" disabled={!month || exportCsv.isPending} onClick={() => exportCsv.mutate()}>
                {exportCsv.isPending ? t('reports.exporting') : t('reports.export')}
              </button>
            </div>
          </div>
        </Card>
      </div>
    </div>
  );
}

// ---------- new claim / edit a returned one ----------

function ClaimForm({ editing, onClose }: { editing: Claim | null; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [today] = useState(() => isoDate(new Date()));
  const types = useQuery({ queryKey: ['claims', 'types'], queryFn: api.claims.types });
  const [typeId, setTypeId] = useState(editing?.claimTypeId ?? '');
  const [date, setDate] = useState(editing?.expenseDate ?? today);
  const [description, setDescription] = useState(editing?.description ?? '');
  const [merchant, setMerchant] = useState(editing?.merchant ?? '');
  const [amount, setAmount] = useState(editing && editing.kind === 'RECEIPT' ? String(Number(editing.amount).toFixed(2)) : '');
  const [km, setKm] = useState(editing?.distanceKm != null ? String(Number(editing.distanceKm)) : '');
  const [routeFrom, setRouteFrom] = useState(editing?.routeFrom ?? '');
  const [routeTo, setRouteTo] = useState(editing?.routeTo ?? '');
  const [receipt, setReceipt] = useState<FileMeta | null>(editing?.receipt ?? null);

  const type: ClaimType | undefined = types.data?.find((x) => x.id === (typeId || types.data?.[0]?.id));
  const mileage = type?.kind === 'MILEAGE';
  const usage = useQuery({ queryKey: ['claims', 'usage', monthKey(date)], queryFn: () => api.claims.usage(monthKey(date)), enabled: !!date });
  const currency = usage.data?.currency ?? editing?.currency ?? 'MYR';
  const limit = usage.data?.types.find((u) => u.claimTypeId === type?.id);

  const value = mileage
    ? Math.round(Number(km || 0) * Number(type?.ratePerKm ?? 0) * 100) / 100
    : Number(amount || 0);
  // When editing, this claim's own amount is already counted in the month's usage.
  const own = editing && editing.claimTypeId === type?.id && monthKey(editing.expenseDate) === monthKey(date) && editing.status === 'PENDING'
    ? Number(editing.amount) : 0;
  const monthLeft = limit?.left == null ? null : Number(limit.left) + own;
  const overClaim = type?.maxPerClaim != null && value > Number(type.maxPerClaim);
  const overMonth = monthLeft !== null && value > monthLeft;
  const needsReceipt = !!type && !mileage && type.receiptRequired;
  const valid = !!type && !!date && description.trim().length > 0 && value > 0 && !overClaim && !overMonth && (!needsReceipt || !!receipt);

  const submit = useMutation({
    mutationFn: () => {
      const input = {
        claimTypeId: type!.id, expenseDate: date, description: description.trim(),
        merchant: mileage ? null : merchant.trim() || null,
        amount: mileage ? null : Number(amount),
        distanceKm: mileage ? Number(km) : null,
        routeFrom: mileage ? routeFrom.trim() || null : null,
        routeTo: mileage ? routeTo.trim() || null : null,
        receiptId: receipt?.id ?? null,
      };
      return editing ? api.claims.resubmit(editing.id, input) : api.claims.submit(input);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['claims'] });
      await queryClient.invalidateQueries({ queryKey: ['approvals'] });
      onClose();
    },
  });

  return (
    <Panel title={editing ? t('claims.form.editTitle') : t('claims.new')} onClose={onClose}>
      <form
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          submit.mutate();
        }}
      >
        {editing?.returnedComment && <p className="notice">{t('claims.form.sentBackNote', { comment: editing.returnedComment })}</p>}
        {submit.error && <p className="form-error" role="alert">{errorText(submit.error)}</p>}
        {types.data && types.data.length === 0 && <p className="notice">{t('claims.form.noTypes')}</p>}

        <label className="field">
          {t('claims.form.type')}
          <select value={type?.id ?? ''} onChange={(e) => setTypeId(e.target.value)} required>
            {types.data?.map((ct) => <option key={ct.id} value={ct.id}>{ct.name}</option>)}
          </select>
        </label>
        <label className="field">
          {t('claims.form.date')}
          <input type="date" value={date} min={addDays(today, -90)} max={today} onChange={(e) => setDate(e.target.value)} required />
        </label>

        {mileage ? (
          <>
            <div className="field-row">
              <label className="field">{t('claims.form.from')}<input value={routeFrom} onChange={(e) => setRouteFrom(e.target.value)} maxLength={120} placeholder={t('claims.form.fromExample')} /></label>
              <label className="field">{t('claims.form.to')}<input value={routeTo} onChange={(e) => setRouteTo(e.target.value)} maxLength={120} placeholder={t('claims.form.toExample')} /></label>
            </div>
            <label className="field">
              {t('claims.form.km')}
              <input type="number" inputMode="decimal" min={0.1} max={5000} step={0.1} value={km} onChange={(e) => setKm(e.target.value)} required />
              <span className="hint">{t('claims.form.kmHint')}</span>
            </label>
          </>
        ) : (
          <div className="field-row">
            <label className="field">
              {t('claims.form.amount', { currency })}
              <input type="number" inputMode="decimal" min={0.01} step={0.01} value={amount} onChange={(e) => setAmount(e.target.value)} required />
            </label>
            <label className="field">{t('claims.form.merchant')}<input value={merchant} onChange={(e) => setMerchant(e.target.value)} maxLength={120} placeholder={t('claims.form.merchantExample')} /></label>
          </div>
        )}

        <label className="field">
          {t('claims.form.description')}
          <input value={description} onChange={(e) => setDescription(e.target.value)} required maxLength={300} placeholder={t('claims.form.descriptionExample')} />
          <span className="hint">{t('claims.form.descriptionHint')}</span>
        </label>

        {!mileage && (
          <FileField
            label={needsReceipt ? t('claims.form.receiptRequired') : t('claims.form.receipt')}
            required={needsReceipt}
            current={receipt}
            onChange={setReceipt}
          />
        )}

        <div className={overClaim || overMonth ? 'apply-summary is-short' : 'apply-summary'} aria-live="polite">
          {value > 0 ? <strong>{formatMoney(value, currency)}</strong> : <span>{mileage ? t('claims.form.enterKm') : t('claims.form.enterAmount')}</span>}
          {mileage && type?.ratePerKm != null && value > 0 && (
            <span>{t('claims.form.mileageMath', { km: Number(km), rate: formatMoney(type.ratePerKm, currency) })}</span>
          )}
          {type?.maxPerClaim != null && (
            <span className={overClaim ? undefined : 'hint'}>
              {overClaim ? t('claims.form.overClaim', { max: formatMoney(type.maxPerClaim, currency) }) : t('claims.form.perClaim', { max: formatMoney(type.maxPerClaim, currency) })}
            </span>
          )}
          {monthLeft !== null && (
            <span className={overMonth ? undefined : 'hint'}>
              {overMonth
                ? t('claims.form.overMonth', { left: formatMoney(monthLeft, currency), month: formatMonth(monthKey(date)) })
                : t('claims.form.monthLeft', { left: formatMoney(Math.max(0, monthLeft - value), currency), month: formatMonth(monthKey(date)) })}
            </span>
          )}
        </div>

        {type && <p className="hint">{t('claims.form.goes', { chain: chainText(type) })}</p>}
        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={!valid || submit.isPending}>
            {submit.isPending ? t('leave.form.sending') : editing ? t('leave.form.resubmit') : t('leave.form.send')}
          </button>
          {needsReceipt && !receipt && <span className="hint">{t('claims.form.attachToContinue')}</span>}
        </div>
      </form>
    </Panel>
  );
}

// ---------- one claim ----------

export function ClaimFacts({ claim: c, showEmployee }: { claim: Claim; showEmployee: boolean }) {
  const { t } = useTranslation();
  return (
    <dl className="facts">
      {showEmployee && <><dt>{t('req.employee')}</dt><dd>{c.employeeName} ({c.employeeNo})</dd></>}
      <dt>{t('claims.facts.amount')}</dt><dd><strong>{formatMoney(c.amount, c.currency)}</strong></dd>
      <dt>{t('claims.facts.type')}</dt><dd>{c.typeName}</dd>
      <dt>{t('claims.facts.date')}</dt><dd>{formatDate(c.expenseDate, { weekday: 'short', day: 'numeric', month: 'long', year: 'numeric' })}</dd>
      <dt>{t('claims.facts.description')}</dt><dd>{c.description}</dd>
      {c.merchant && <><dt>{t('claims.facts.merchant')}</dt><dd>{c.merchant}</dd></>}
      {c.distanceKm != null && (
        <>
          <dt>{t('claims.facts.trip')}</dt>
          <dd>{[c.routeFrom && c.routeTo ? `${c.routeFrom} → ${c.routeTo}` : c.routeFrom ?? c.routeTo, t('claims.facts.km', { km: Number(c.distanceKm) })].filter(Boolean).join(' · ')}</dd>
        </>
      )}
      {c.kind === 'RECEIPT' && (
        <><dt>{t('claims.facts.receipt')}</dt><dd>{c.receipt ? <AttachmentButton requestId={c.id} file={c.receipt} load={api.claims.receipt} /> : '—'}</dd></>
      )}
      <dt>{t('req.submitted')}</dt><dd>{relative(c.createdAt)}</dd>
      {c.paidAt && <><dt>{t('claims.facts.paid')}</dt><dd>{[relative(c.paidAt), c.paidBy, c.paymentRef && t('claims.facts.ref', { ref: c.paymentRef })].filter(Boolean).join(' · ')}</dd></>}
    </dl>
  );
}

function ClaimPanel({ id, meId, onEdit, onClose }: { id: string; meId: string | null; onEdit: (c: Claim) => void; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const detail = useQuery({ queryKey: ['claims', 'detail', id], queryFn: () => api.claims.detail(id) });
  const cancel = useMutation({
    mutationFn: () => api.claims.cancel(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['claims'] });
      onClose();
    },
  });
  const c = detail.data?.claim;
  const mine = !!c && c.employeeId === meId;
  const error = detail.error ?? cancel.error;

  return (
    <Panel title={c ? `${c.typeName}, ${formatMoney(c.amount, c.currency)}` : t('claims.claim')} onClose={onClose}>
      {error && <p className="form-error" role="alert">{errorText(error)}</p>}
      {c && (
        <>
          {c.status === 'RETURNED' && c.returnedComment && <p className="notice">{t('claims.sentBackNotice', { comment: c.returnedComment })}</p>}
          <div><ClaimStatus claim={c} /></div>
          <ClaimFacts claim={c} showEmployee={!mine} />
          {detail.data!.timeline.length > 0 && (
            <section className="stack">
              <h3 className="panel-subhead">{t('req.chain')}</h3>
              <Timeline steps={detail.data!.timeline} />
            </section>
          )}
          {mine && (c.canEdit || c.canCancel) && (
            <div className="panel-actions">
              {c.canEdit && <button type="button" className="btn primary" onClick={() => onEdit(c)}>{t('claims.editResubmit')}</button>}
              {c.canCancel && (
                <button type="button" className="btn reject" onClick={() => cancel.mutate()} disabled={cancel.isPending}>{t('claims.withdraw')}</button>
              )}
            </div>
          )}
        </>
      )}
    </Panel>
  );
}
