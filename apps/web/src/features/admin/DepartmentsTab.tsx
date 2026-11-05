import { useState } from 'react';
import { Link } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { Department } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { Icon } from '../../components/Icon';
import { Avatar, Card, EmptyState, List, Row, Status } from '../../components/app/kit';
import { useOpenParam } from '../../lib/openParam';

const TONES = ['#DBEAFE', '#DCFCE7', '#FEF3C7', '#FCE7F3', '#EDE9FE', '#CFFAFE', '#FFE4E6'];
const INK = ['#1D4ED8', '#15803D', '#B45309', '#BE185D', '#6D28D9', '#0E7490', '#BE123C'];

function deptInitials(name: string) {
  const words = name.trim().split(/\s+/);
  return ((words[0]?.[0] ?? '') + (words[1]?.[0] ?? words[0]?.[1] ?? '')).toUpperCase();
}

/** A coloured square with the department's initials; the same name always gets the same colour. */
function DeptMark({ name, size = 36 }: { name: string; size?: number }) {
  const seed = [...name].reduce((n, c) => n + c.charCodeAt(0), 0) % TONES.length;
  return (
    <span className="dept-mark" aria-hidden="true" style={{ width: size, height: size, background: TONES[seed], color: INK[seed], fontSize: size * 0.36 }}>
      {deptInitials(name)}
    </span>
  );
}

/** Departments: pick one to see its people, rename it, or delete it when it's empty. */
export function DepartmentsTab() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const departments = useQuery({ queryKey: ['admin', 'departments'], queryFn: api.admin.departments });
  const [q, setQ] = useState('');
  const [picked, setPicked] = useState<string | null>(null);
  // Opened from search: /app/admin/company?tab=departments&open=<department id>
  const [openId, clearOpen] = useOpenParam();
  const selected = openId ?? picked;
  const setSelected = (id: string | null) => {
    setPicked(id);
    clearOpen();
  };
  const [adding, setAdding] = useState(false);
  const [newName, setNewName] = useState('');

  const add = useMutation({
    mutationFn: () => api.admin.addDepartment(newName.trim()),
    onSuccess: async (d) => {
      setNewName('');
      setAdding(false);
      await queryClient.invalidateQueries({ queryKey: ['admin', 'departments'] });
      setSelected(d.id);
    },
  });

  const list = departments.data ?? [];
  const shown = list.filter((d) => !q || d.name.toLowerCase().includes(q.toLowerCase()));
  const current = list.find((d) => d.id === selected) ?? shown[0] ?? null;
  const people = list.reduce((n, d) => n + d.employeeCount, 0);

  return (
    <div className="dept-layout">
      <Card title={t('users.departments')} count={list.length}>
        <div className="card-pad dept-tools">
          <input className="search-input" type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('dept.search')} aria-label={t('dept.search')} />
        </div>
        {departments.isError && <p className="form-error card-pad" role="alert">{errorText(departments.error)}</p>}
        <List>
          {shown.map((d) => (
            <li key={d.id}>
              <button type="button" className={current?.id === d.id ? 'ui-row ui-row--link is-selected' : 'ui-row ui-row--link'}
                aria-current={current?.id === d.id} onClick={() => setSelected(d.id)}>
                <span className="ui-row__lead"><DeptMark name={d.name} /></span>
                <span className="ui-row__text">
                  <span className="ui-row__title">{d.name}</span>
                  <span className="ui-row__meta">{t('common.person', { count: d.employeeCount })}</span>
                </span>
              </button>
            </li>
          ))}
          {departments.data && shown.length === 0 && <li className="ui-row hint">{q ? t('dept.noMatch', { q }) : t('dept.none')}</li>}
        </List>
        <div className="dept-add">
          {adding ? (
            <form
              className="dept-add__form"
              onSubmit={(e) => {
                e.preventDefault();
                if (newName.trim()) add.mutate();
              }}
            >
              <input className="input" value={newName} onChange={(e) => setNewName(e.target.value)} placeholder={t('dept.name')}
                aria-label={t('dept.newName')} autoFocus maxLength={80} required />
              <button type="submit" className="btn small primary" disabled={add.isPending || !newName.trim()}>{t('common.add')}</button>
              <button type="button" className="link-button" onClick={() => { setAdding(false); setNewName(''); add.reset(); }}>{t('common.cancel')}</button>
              {add.error && <p className="form-error" role="alert">{errorText(add.error)}</p>}
            </form>
          ) : (
            <button type="button" className="btn small" onClick={() => setAdding(true)}><Icon name="plus" size={14} />{t('dept.add')}</button>
          )}
          {list.length > 0 && <p className="hint">{t('dept.across', { people: t('common.person', { count: people }), depts: t('dept.count', { count: list.length }) })}</p>}
        </div>
      </Card>

      {current ? (
        <DepartmentDetail key={current.id} dept={current} onDeleted={() => setSelected(null)} />
      ) : (
        <Card>
          <EmptyState icon="building" tone="green" title={t('dept.emptyTitle')}
            action={<button type="button" className="btn primary" onClick={() => setAdding(true)}>{t('dept.addFirst')}</button>}>
            {t('dept.emptyBody')}
          </EmptyState>
        </Card>
      )}
    </div>
  );
}

function DepartmentDetail({ dept, onDeleted }: { dept: Department; onDeleted: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const members = useQuery({ queryKey: ['admin', 'departments', dept.id, 'members'], queryFn: () => api.admin.departmentMembers(dept.id) });
  const [renaming, setRenaming] = useState(false);
  const [name, setName] = useState(dept.name);
  const [confirming, setConfirming] = useState(false);

  const rename = useMutation({
    mutationFn: () => api.admin.renameDepartment(dept.id, name.trim()),
    onSuccess: async () => {
      setRenaming(false);
      await queryClient.invalidateQueries({ queryKey: ['admin', 'departments'] });
    },
  });
  const remove = useMutation({
    mutationFn: () => api.admin.deleteDepartment(dept.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin', 'departments'] });
      onDeleted();
    },
  });
  const blocked = dept.employeeCount > 0 || dept.workflowCount > 0;
  const error = rename.error ?? remove.error;

  return (
    <Card>
      <header className="dept-head">
        <DeptMark name={dept.name} size={52} />
        <div className="dept-head__text">
          {renaming ? (
            <form
              className="dept-rename"
              onSubmit={(e) => {
                e.preventDefault();
                if (name.trim()) rename.mutate();
              }}
            >
              <input className="input" value={name} onChange={(e) => setName(e.target.value)} aria-label={t('dept.name')} autoFocus maxLength={80} required />
              <button type="submit" className="btn small primary" disabled={rename.isPending || !name.trim() || name.trim() === dept.name}>{t('common.save')}</button>
              <button type="button" className="link-button" onClick={() => { setRenaming(false); setName(dept.name); rename.reset(); }}>{t('common.cancel')}</button>
            </form>
          ) : (
            <h2>{dept.name}</h2>
          )}
          <span className="hint">{t('dept.used')}</span>
        </div>
        {!renaming && (
          <div className="dept-head__actions">
            <button type="button" className="btn small" onClick={() => setRenaming(true)}>{t('common.rename')}</button>
            <button type="button" className="btn small reject" onClick={() => setConfirming((v) => !v)} aria-expanded={confirming}>{t('common.delete')}</button>
          </div>
        )}
      </header>

      {error && <p className="form-error card-pad dept-error" role="alert">{errorText(error)}</p>}

      {confirming && (
        <div className={blocked ? 'dept-confirm is-blocked' : 'dept-confirm'} role="alert">
          {blocked ? (
            <>
              <strong>{t('dept.cantDelete')}</strong>
              <ul>
                {dept.employeeCount > 0 && <li>{t('dept.hasPeople', { count: dept.employeeCount })} <Link to="/app/admin/users">{t('nav.users')}</Link></li>}
                {dept.workflowCount > 0 && <li>{t('dept.hasFlows', { count: dept.workflowCount })} <Link to="/app/admin/workflows">{t('nav.workflows')}</Link></li>}
              </ul>
              <button type="button" className="btn small" onClick={() => setConfirming(false)}>{t('common.ok')}</button>
            </>
          ) : (
            <>
              <strong>{t('dept.deleteQ', { name: dept.name })}</strong>
              <span>{t('dept.deleteBody')}</span>
              <span className="panel-actions">
                <button type="button" className="btn small reject" onClick={() => remove.mutate()} disabled={remove.isPending}>{t('dept.deleteBtn')}</button>
                <button type="button" className="link-button" onClick={() => setConfirming(false)}>{t('dept.keep')}</button>
              </span>
            </>
          )}
        </div>
      )}

      <div className="dept-stats">
        <div><strong>{dept.employeeCount}</strong><span>{t('dept.statPeople', { count: dept.employeeCount })}</span></div>
        <div><strong>{dept.managerCount}</strong><span>{t('dept.statManagers', { count: dept.managerCount })}</span></div>
        <div><strong>{dept.workflowCount}</strong><span>{t('dept.statFlows', { count: dept.workflowCount })}</span></div>
      </div>

      <div className="dept-members">
        <div className="dept-members__head">
          <h3>{t('dept.peopleIn')}</h3>
          <Link className="ui-card__link" to="/app/admin/users">{t('dept.manageInUsers')}</Link>
        </div>
        {members.isError && <p className="form-error" role="alert">{errorText(members.error)}</p>}
        <List>
          {members.data?.map((m) => (
            <Row
              key={m.employeeId}
              lead={<Avatar name={m.fullName} size={34} />}
              title={m.fullName}
              meta={[m.employeeNo, m.email, m.manager ? t('dashboard.reportsTo', { name: m.manager }) : t('users.noManager')].filter(Boolean).join(' · ')}
              trail={
                <>
                  {m.managesOthers && <Status tone="blue">{t('common.manager')}</Status>}
                  {m.status !== 'ACTIVE' && <Status tone="grey">{t('common.inactive')}</Status>}
                </>
              }
            />
          ))}
          {members.data && members.data.length === 0 && (
            <Row title={t('dept.nobody')} meta={t('dept.nobodyMeta')} />
          )}
          {members.isPending && <Row title={t('common.loading')} />}
        </List>
      </div>
    </Card>
  );
}
