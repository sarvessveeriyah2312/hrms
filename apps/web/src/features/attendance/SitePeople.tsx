import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { SiteDetail, SiteMember } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { usePaging } from '../../lib/paging';
import { Icon } from '../../components/Icon';
import { Avatar, Card, EmptyState } from '../../components/app/kit';
import { Pager } from '../../components/app/Pager';

const NO_DEPT = '';

/** Who may clock in at a site: search, remove several at once, add people one by one or a whole department. */
export function SitePeople({ site }: { site: SiteDetail }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [q, setQ] = useState('');
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  const [adding, setAdding] = useState(false);
  const words = q.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const shown = site.members.filter((m) => words.every((w) => `${m.fullName} ${m.employeeNo} ${m.department ?? ''}`.toLowerCase().includes(w)));
  const paging = usePaging(shown, { pageSize: 10, resetKey: q });

  const update = (members: SiteMember[]) => {
    queryClient.setQueryData<SiteDetail>(['sites', site.id], (s) => (s ? { ...s, members } : s));
    void queryClient.invalidateQueries({ queryKey: ['sites'], exact: true });
  };
  const remove = useMutation({
    mutationFn: (ids: string[]) => api.sites.removeMembers(site.id, ids),
    onSuccess: (members) => { setChosen(new Set()); update(members); },
  });
  const toggle = (id: string) => setChosen((s) => {
    const next = new Set(s);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });
  const pageIds = paging.visible.map((m) => m.employeeId);
  const allOnPage = pageIds.length > 0 && pageIds.every((id) => chosen.has(id));

  return (
    <Card title={t('sites.peopleTitle')} count={site.members.length}
      action={<button type="button" className="btn small primary" onClick={() => setAdding(true)}><Icon name="plus" size={14} />{t('sites.addPeople')}</button>}>
      <div className="card-pad site-people__intro">
        <p className="hint">{t('sites.peopleHint')}</p>
        {site.members.length > 0 && (
          <input className="search-input" type="search" value={q} onChange={(e) => setQ(e.target.value)}
            placeholder={t('sites.searchPeople')} aria-label={t('sites.searchPeople')} />
        )}
      </div>
      {remove.error && <p className="form-error card-pad" role="alert">{errorText(remove.error)}</p>}

      {site.members.length === 0 ? (
        <EmptyState icon="users" tone="blue" title={t('sites.nobody')}
          action={<button type="button" className="btn" onClick={() => setAdding(true)}>{t('sites.addPeople')}</button>}>
          {t('sites.nobodyBody')}
        </EmptyState>
      ) : (
        <>
          <div className="site-people__bar">
            <label className="check-line">
              <input type="checkbox" checked={allOnPage}
                onChange={() => setChosen((s) => {
                  const next = new Set(s);
                  pageIds.forEach((id) => (allOnPage ? next.delete(id) : next.add(id)));
                  return next;
                })} />
              {t('sites.selectPage')}
            </label>
            {chosen.size > 0 && (
              <button type="button" className="btn small reject" onClick={() => remove.mutate([...chosen])} disabled={remove.isPending}>
                {t('sites.removeN', { count: chosen.size })}
              </button>
            )}
          </div>
          <ul className="ui-list">
            {paging.visible.map((m) => (
              <li key={m.employeeId} className={chosen.has(m.employeeId) ? 'site-person is-on' : 'site-person'}>
                <label className="site-person__check">
                  <input type="checkbox" checked={chosen.has(m.employeeId)} onChange={() => toggle(m.employeeId)}
                    aria-label={t('sites.selectOne', { name: m.fullName })} />
                </label>
                <Avatar name={m.fullName} size={32} />
                <span className="ui-row__text">
                  <span className="ui-row__title">{m.fullName}</span>
                  <span className="ui-row__meta">
                    {[m.employeeNo, m.department, m.otherSites.length ? t('sites.alsoAt', { sites: m.otherSites.join(', ') }) : null].filter(Boolean).join(' · ')}
                  </span>
                </span>
                <button type="button" className="icon-pill" onClick={() => remove.mutate([m.employeeId])} disabled={remove.isPending}
                  aria-label={t('sites.removeOne', { name: m.fullName })}><Icon name="close" size={14} /></button>
              </li>
            ))}
            {shown.length === 0 && <li className="ui-row hint">{t('ent.noMatch', { q })}</li>}
          </ul>
          <Pager {...paging} label={t('sites.peopleTitle')} pageSizes={[10, 25, 50]} />
        </>
      )}

      {adding && <AddPeople site={site} onClose={() => setAdding(false)} onAdded={update} />}
    </Card>
  );
}

/** Pick people (search, or a whole department) who aren't at this site yet. */
function AddPeople({ site, onClose, onAdded }: { site: SiteDetail; onClose: () => void; onAdded: (m: SiteMember[]) => void }) {
  const { t } = useTranslation();
  const people = useQuery({ queryKey: ['employees', ''], queryFn: () => api.employees() });
  const [q, setQ] = useState('');
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const already = useMemo(() => new Set(site.members.map((m) => m.employeeId)), [site.members]);
  const candidates = (people.data?.employees ?? []).filter((e) => e.status === 'ACTIVE' && !already.has(e.id));
  const words = q.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const shown = candidates.filter((e) => words.every((w) => `${e.fullName} ${e.employeeNo} ${e.department ?? ''}`.toLowerCase().includes(w)));
  const departments = [...new Set(candidates.map((e) => e.department ?? NO_DEPT))].sort();
  const add = useMutation({
    mutationFn: () => api.sites.addMembers(site.id, [...picked]),
    onSuccess: (members) => { onAdded(members); onClose(); },
  });
  const toggle = (id: string) => setPicked((s) => {
    const next = new Set(s);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });
  const pickDept = (d: string) => setPicked((s) => {
    const next = new Set(s);
    candidates.filter((e) => (e.department ?? NO_DEPT) === d).forEach((e) => next.add(e.id));
    return next;
  });

  return (
    <div className="site-add" role="dialog" aria-modal="false" aria-label={t('sites.addPeople')}>
      <div className="site-add__head">
        <strong>{t('sites.addTo', { site: site.name })}</strong>
        <button type="button" className="icon-pill" onClick={onClose} aria-label={t('common.close')}><Icon name="close" size={14} /></button>
      </div>
      <div className="site-add__tools">
        <input className="search-input" type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('sites.searchPeople')}
          aria-label={t('sites.searchPeople')} autoFocus />
        {departments.length > 0 && (
          <select className="input site-add__dept" value="" onChange={(e) => { if (e.target.value !== '#') pickDept(e.target.value); }}
            aria-label={t('sites.addDept')}>
            <option value="#">{t('sites.addDept')}</option>
            {departments.map((d) => <option key={d || 'none'} value={d}>{d || t('reports.noDept')}</option>)}
          </select>
        )}
      </div>
      {people.isError && <p className="form-error" role="alert">{errorText(people.error)}</p>}
      <ul className="site-add__list">
        {shown.slice(0, 100).map((e) => (
          <li key={e.id}>
            <label className={picked.has(e.id) ? 'site-add__item is-on' : 'site-add__item'}>
              <input type="checkbox" checked={picked.has(e.id)} onChange={() => toggle(e.id)} />
              <Avatar name={e.fullName} size={28} />
              <span className="ui-row__text">
                <span className="ui-row__title">{e.fullName}</span>
                <span className="ui-row__meta">{[e.employeeNo, e.department].filter(Boolean).join(' · ')}</span>
              </span>
            </label>
          </li>
        ))}
        {people.data && candidates.length === 0 && <li className="hint">{t('sites.everyoneHere')}</li>}
        {people.data && candidates.length > 0 && shown.length === 0 && <li className="hint">{t('ent.noMatch', { q })}</li>}
        {shown.length > 100 && <li className="hint">{t('sites.narrow', { n: shown.length - 100 })}</li>}
      </ul>
      {add.error && <p className="form-error" role="alert">{errorText(add.error)}</p>}
      <div className="site-add__foot">
        <span className="hint">{t('sites.pickedN', { count: picked.size })}</span>
        {picked.size > 0 && <button type="button" className="link-button" onClick={() => setPicked(new Set())}>{t('sites.clearPicked')}</button>}
        <button type="button" className="btn primary" onClick={() => add.mutate()} disabled={picked.size === 0 || add.isPending}>
          {add.isPending ? t('common.saving') : t('sites.addN', { count: picked.size })}
        </button>
      </div>
    </div>
  );
}
