import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { LeaveBalance, LeaveType } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { formatDate, toTone } from '../../lib/dates';
import { Glyph } from '../../components/Glyph';
import { Avatar, Card, EmptyState, List, Status } from '../../components/app/kit';
import { YearEndCard } from './YearEndCard';

function n(v: number | null | undefined) {
  const x = Number(v ?? 0);
  return Number.isInteger(x) ? String(x) : x.toFixed(1);
}

/**
 * Per-person entitlements. Pick someone on the left; on the right each leave type shows what the rules give,
 * what's been used, and fields to set a different figure (leave a field empty to use the rules).
 */
export function EntitlementsTab({ types }: { types: LeaveType[] }) {
  const { t } = useTranslation();
  const [thisYear] = useState(() => new Date().getFullYear());
  const [year, setYear] = useState(thisYear);
  const [q, setQ] = useState('');
  const [employeeId, setEmployeeId] = useState<string | null>(null);
  const people = useQuery({ queryKey: ['employees', ''], queryFn: () => api.employees() });
  const shown = (people.data?.employees ?? []).filter((e) =>
    !q || e.fullName.toLowerCase().includes(q.toLowerCase()) || e.employeeNo.toLowerCase().includes(q.toLowerCase()));
  const person = people.data?.employees.find((e) => e.id === employeeId);

  return (
    <div className="ent-layout">
      <div className="col">
        <Card title={t('nav.people')} count={people.data?.employees.length}>
          <div className="card-pad">
            <input className="search-input ent-search" type="search" value={q} onChange={(e) => setQ(e.target.value)}
              placeholder={t('ent.search')} aria-label={t('shell.searchPeople')} />
          </div>
          {people.isError && <p className="form-error card-pad" role="alert">{errorText(people.error)}</p>}
          <List>
            {shown.map((e) => (
              <li key={e.id}>
                <button type="button" className={e.id === employeeId ? 'ui-row ui-row--link is-selected' : 'ui-row ui-row--link'}
                  aria-current={e.id === employeeId} onClick={() => setEmployeeId(e.id)}>
                  <span className="ui-row__lead"><Avatar name={e.fullName} size={32} /></span>
                  <span className="ui-row__text">
                    <span className="ui-row__title">{e.fullName}</span>
                    <span className="ui-row__meta">{[e.employeeNo, e.department].filter(Boolean).join(' · ')}</span>
                  </span>
                </button>
              </li>
            ))}
            {people.data && shown.length === 0 && <li className="ui-row hint">{t('ent.noMatch', { q })}</li>}
          </List>
        </Card>
        <YearEndCard types={types} />
      </div>

      {person ? (
        <PersonEntitlements key={`${person.id}-${year}`} employeeId={person.id} name={person.fullName}
          meta={[person.employeeNo, person.department, person.manager && t('dashboard.reportsTo', { name: person.manager })].filter(Boolean).join(' · ')}
          year={year} thisYear={thisYear} onYear={setYear} />
      ) : (
        <Card>
          <EmptyState icon="users" tone="green" title={t('ent.choose')}>
            {t('ent.chooseBody')}
          </EmptyState>
        </Card>
      )}
    </div>
  );
}

function PersonEntitlements({ employeeId, name, meta, year, thisYear, onYear }: {
  employeeId: string; name: string; meta: string; year: number; thisYear: number; onYear: (y: number) => void;
}) {
  const { t } = useTranslation();
  const balances = useQuery({
    queryKey: ['leave', 'entitlements', employeeId, year],
    queryFn: () => api.leave.settings.entitlements(employeeId, year),
  });
  const tracked = (balances.data ?? []).filter((b) => b.trackBalance);
  const custom = tracked.filter((b) => b.source === 'MANUAL' || b.carriedSource === 'MANUAL').length;

  return (
    <Card>
      <header className="ent-person">
        <Avatar name={name} size={44} />
        <div className="ent-person__text">
          <h2>{name}</h2>
          <span className="hint">{meta}</span>
        </div>
        <div className="ent-person__end">
          {custom > 0 && <Status tone="blue">{t('ent.nSetByHr', { n: custom })}</Status>}
          <select className="input ent-year" value={year} onChange={(e) => onYear(Number(e.target.value))} aria-label={t('ent.year')}>
            {[thisYear - 1, thisYear, thisYear + 1].map((y) => <option key={y} value={y}>{y}</option>)}
          </select>
        </div>
      </header>
      {balances.isError && <p className="form-error card-pad" role="alert">{errorText(balances.error)}</p>}
      <div className="ent-cards">
        {tracked.map((b) => <TypeCard key={b.leaveTypeId} employeeId={employeeId} year={year} balance={b} />)}
        {balances.data && tracked.length === 0 && <p className="hint">{t('ent.noTracked')}</p>}
      </div>
    </Card>
  );
}

function TypeCard({ employeeId, year, balance }: { employeeId: string; year: number; balance: LeaveBalance }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const manualEntitled = balance.source === 'MANUAL' ? n(balance.entitled) : '';
  const [entitled, setEntitled] = useState(manualEntitled);
  const [carried, setCarried] = useState(n(balance.carried));
  const [note, setNote] = useState(balance.note ?? '');
  const dirty = entitled !== manualEntitled || carried !== n(balance.carried) || note !== (balance.note ?? '');
  const overridden = balance.source === 'MANUAL' || balance.carriedSource === 'MANUAL';

  const refresh = (data: LeaveBalance[]) => {
    queryClient.setQueryData(['leave', 'entitlements', employeeId, year], data);
    void queryClient.invalidateQueries({ queryKey: ['leave', 'balances'] });
    void queryClient.invalidateQueries({ queryKey: ['leave', 'report'] });
  };
  const save = useMutation({
    mutationFn: () => api.leave.settings.setEntitlement(employeeId, balance.leaveTypeId, year, {
      entitled: entitled === '' ? null : Number(entitled),
      carried: carried === n(balance.carried) ? null : Number(carried || 0),
      note: note || undefined,
    }),
    onSuccess: refresh,
  });
  const reset = useMutation({
    mutationFn: () => api.leave.settings.resetEntitlement(employeeId, balance.leaveTypeId, year),
    onSuccess: refresh,
  });
  const error = save.error ?? reset.error;
  const total = Number(balance.entitled) + Number(balance.carried);

  return (
    <form
      className="ent-card"
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate();
      }}
    >
      <header className="ent-card__head">
        <Glyph icon="calendar" tone={toTone(balance.tone)} />
        <strong>{balance.name}</strong>
        <Status tone={balance.source === 'MANUAL' ? 'blue' : 'grey'}>{balance.source === 'MANUAL' ? t('ent.setByHr') : t('ent.byRules')}</Status>
      </header>

      <div className="ent-card__figures">
        <div className="ent-left">
          <span className={Number(balance.available) < 0 ? 'ent-left__value is-negative' : 'ent-left__value'}>{n(balance.available)}</span>
          <span className="hint">{t('leave.ofLeft', { total: n(total) })}</span>
        </div>
        <dl className="ent-facts">
          <div><dt>{t('ent.entitled')}</dt><dd>{n(balance.entitled)}</dd></div>
          <div><dt>{t('ent.carriedIn')}</dt><dd>{n(balance.carried)}</dd></div>
          <div><dt>{t('ent.taken')}</dt><dd>{n(balance.used)}</dd></div>
          <div><dt>{t('ent.waiting')}</dt><dd>{n(balance.pending)}</dd></div>
        </dl>
      </div>

      <p className="ent-rule">
        {t('ent.rulesGive')} <strong>{t('common.day', { count: Number(balance.policyDays) })}</strong>
        {balance.source === 'MANUAL' ? '' : ` (${balance.basis})`}
        {Number(balance.carriedExpired) > 0 && balance.carriedExpiresOn
          ? ` · ${t('ent.expired', { n: n(balance.carriedExpired), date: formatDate(balance.carriedExpiresOn, { day: 'numeric', month: 'short' }) })}`
          : ''}
        {balance.carriedSource === 'YEAR_END' ? ` · ${t('ent.fromYearEnd')}` : ''}
      </p>

      <div className="ent-inputs">
        <label className="field">
          {t('ent.entitledDays')}
          <input inputMode="decimal" value={entitled} placeholder={t('ent.rulesPlaceholder', { n: n(balance.policyDays) })}
            onChange={(e) => setEntitled(e.target.value)} />
        </label>
        <label className="field">
          {t('ent.carriedIn')}
          <input inputMode="decimal" value={carried} onChange={(e) => setCarried(e.target.value)} />
        </label>
        {(entitled !== '' || balance.source === 'MANUAL') && (
          <label className="field ent-inputs__note">
            {t('ent.why')}
            <input value={note} onChange={(e) => setNote(e.target.value)} placeholder={t('ent.whyExample')} maxLength={200} />
          </label>
        )}
      </div>

      {error && <p className="form-error" role="alert">{errorText(error)}</p>}
      <div className="panel-actions">
        <button type="submit" className="btn small primary" disabled={!dirty || save.isPending}>{save.isPending ? t('common.saving') : t('common.save')}</button>
        {overridden && (
          <button type="button" className="btn small" onClick={() => reset.mutate()} disabled={reset.isPending}>{t('ent.backToRules')}</button>
        )}
      </div>
    </form>
  );
}
