import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { useOpenParam } from '../../lib/openParam';
import { useTranslation } from 'react-i18next';
import i18n, { dateLocale } from '../../i18n';
import type { AdminRole, AdminUser, AdminUserInput, Department } from '@hrms/api-client';
import { api, reloadSession } from '../../lib/api';
import { errorText, useAccess } from '../../lib/access';
import { Panel, TemporaryPassword } from './Panel';
import { roleLabel, roleText } from '../../lib/modules';
import { Icon } from '../../components/Icon';
import { Avatar, Card, List, PageHeader, Row, StatCard, Status } from '../../components/app/kit';
import './admin.css';

type Editing = { mode: 'new' } | { mode: 'edit'; user: AdminUser };

function blankInput(): AdminUserInput {
  return { fullName: '', email: '', phone: '', employeeNo: '', departmentId: null, managerId: null, backupApproverId: null, roleIds: [] };
}

function toInput(u: AdminUser): AdminUserInput {
  return {
    fullName: u.fullName ?? '', email: u.email ?? '', phone: u.phone ?? '', employeeNo: u.employeeNo ?? '',
    departmentId: u.departmentId, managerId: u.managerId, backupApproverId: u.backupApproverId,
    roleIds: u.roleIds, status: u.status,
  };
}

function lastSeen(iso: string | null) {
  if (!iso) return i18n.t('users.never');
  return new Date(iso).toLocaleString(dateLocale(), { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

/** Users: login details, employee record, approvers (manager and backup) and roles. */
export function UsersPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const [q, setQ] = useState('');
  const [editingState, setEditingState] = useState<Editing | null>(() => (params.get('new') ? { mode: 'new' } : null));
  const [openId, clearOpen] = useOpenParam();
  const users = useQuery({ queryKey: ['admin', 'users', q], queryFn: () => api.admin.users(q) });
  const roles = useQuery({ queryKey: ['admin', 'roles'], queryFn: api.admin.roles });
  const departments = useQuery({ queryKey: ['admin', 'departments'], queryFn: api.admin.departments });
  const active = users.data?.filter((u) => u.status === 'ACTIVE').length ?? 0;
  // Opened from search: /app/admin/users?open=<user id>
  const linked = openId ? users.data?.find((u) => u.userId === openId) : undefined;
  const editing: Editing | null = editingState ?? (linked ? { mode: 'edit', user: linked } : null);
  const setEditing = (e: Editing | null) => {
    setEditingState(e);
    clearOpen();
  };

  const close = () => {
    setEditing(null);
    if (params.get('new')) setParams({}, { replace: true });
  };

  return (
    <>
      <PageHeader
        title={t('nav.users')}
        subtitle={t('users.subtitle')}
        actions={<button type="button" className="btn primary" onClick={() => setEditing({ mode: 'new' })}><Icon name="plus" size={16} />{t('users.add')}</button>}
      />

      <div className="grid grid--stats">
        <StatCard icon="users" label={t('nav.users')} value={users.data?.length ?? '–'} note={t('users.nActive', { n: active })} />
        <StatCard icon="shield" label={t('users.roles')} value={roles.data?.length ?? '–'} note={t('users.rolesNote')} />
        <StatCard icon="building" label={t('users.departments')} value={departments.data?.length ?? '–'} note={t('users.deptNote')} />
      </div>

      {users.isError && <p className="form-error" role="alert">{errorText(users.error)}</p>}

      <Card
        title={t('users.everyone')}
        count={users.data?.length}
        action={<input className="search-input" type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('users.search')} aria-label={t('users.searchLabel')} />}
      >
        <List>
          {users.data?.map((u) => (
            <Row
              key={u.userId}
              lead={<Avatar name={u.fullName ?? u.email} size={36} />}
              title={u.fullName ?? u.email ?? t('users.unnamed')}
              meta={[u.email ?? u.phone, u.employeeNo, u.department, u.manager ? t('users.managerIs', { name: u.manager }) : t('users.noManager'), t('users.lastSignIn', { when: lastSeen(u.lastLoginAt) })].filter(Boolean).join(' · ')}
              trail={
                <>
                  {u.roles.map((r) => <span key={r} className="chip neutral">{roleLabel(r)}</span>)}
                  <Status tone={u.status === 'ACTIVE' ? 'green' : 'red'}>{u.status === 'ACTIVE' ? t('common.active') : t('users.disabled')}</Status>
                  <button type="button" className="btn small" onClick={() => setEditing({ mode: 'edit', user: u })} aria-label={t('users.editName', { name: u.fullName ?? u.email })}>{t('common.edit')}</button>
                </>
              }
            />
          ))}
          {users.data && users.data.length === 0 && <Row title={t('people.noMatch', { q })} />}
          {users.isPending && <Row title={t('common.loading')} />}
        </List>
      </Card>

      {editing && (
        <UserEditor
          key={editing.mode === 'edit' ? editing.user.userId : 'new'}
          editing={editing}
          roles={roles.data ?? []}
          departments={departments.data ?? []}
          people={users.data ?? []}
          onClose={close}
        />
      )}
    </>
  );
}

function UserEditor({ editing, roles, departments, people, onClose }: {
  editing: Editing; roles: AdminRole[]; departments: Department[]; people: AdminUser[]; onClose: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { user: me } = useAccess();
  const existing = editing.mode === 'edit' ? editing.user : null;
  const [input, setInput] = useState<AdminUserInput>(existing ? toInput(existing) : blankInput());
  const [password, setPassword] = useState<{ value: string; reset: boolean } | null>(null);
  const isSelf = existing?.userId === me?.userId;

  const set = <K extends keyof AdminUserInput>(key: K, value: AdminUserInput[K]) => setInput((v) => ({ ...v, [key]: value }));
  const toggleRole = (id: string) =>
    set('roleIds', input.roleIds.includes(id) ? input.roleIds.filter((r) => r !== id) : [...input.roleIds, id]);

  const afterChange = async () => {
    await queryClient.invalidateQueries({ queryKey: ['admin'] });
    if (isSelf) await reloadSession(); // your own roles changed: get a token with the new permissions
  };

  const save = useMutation({
    mutationFn: async () => {
      if (existing) return { user: await api.admin.updateUser(existing.userId, input), temporaryPassword: null };
      return api.admin.createUser(input);
    },
    onSuccess: async (result) => {
      await afterChange();
      if (result.temporaryPassword) setPassword({ value: result.temporaryPassword, reset: false });
      else onClose();
    },
  });

  const reset = useMutation({
    mutationFn: () => api.admin.resetPassword(existing!.userId),
    onSuccess: (r) => setPassword({ value: r.temporaryPassword, reset: true }),
  });

  // Approver options: active employees other than this person.
  const approvers = people.filter((p) => p.employeeId && p.status === 'ACTIVE' && p.employeeId !== existing?.employeeId);
  const error = save.error ?? reset.error;

  return (
    <Panel title={existing ? t('users.editName', { name: existing.fullName ?? t('users.user') }) : t('users.add')} onClose={onClose}>
      {password ? (
        <>
          <TemporaryPassword name={input.fullName} password={password.value} reset={password.reset} />
          <button type="button" className="btn primary" onClick={onClose}>{t('users.done')}</button>
        </>
      ) : (
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            save.mutate();
          }}
        >
          {error && <p className="form-error" role="alert">{errorText(error)}</p>}

          <fieldset>
            <legend>{t('users.person')}</legend>
            <label className="field">{t('users.fullName')}<input value={input.fullName} onChange={(e) => set('fullName', e.target.value)} required /></label>
            <div className="field-row">
              <label className="field">{t('users.employeeNo')}<input value={input.employeeNo} onChange={(e) => set('employeeNo', e.target.value)} required /></label>
              <label className="field">
                {t('reports.department')}
                <select value={input.departmentId ?? ''} onChange={(e) => set('departmentId', e.target.value || null)}>
                  <option value="">{t('reports.noDept')}</option>
                  {departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
                </select>
              </label>
            </div>
          </fieldset>

          <fieldset>
            <legend>{t('users.signIn')}</legend>
            <p className="hint">{t('users.signInHint')}</p>
            <div className="field-row">
              <label className="field">{t('users.email')}<input type="email" value={input.email ?? ''} onChange={(e) => set('email', e.target.value)} /></label>
              <label className="field">{t('users.mobile')}<input type="tel" value={input.phone ?? ''} onChange={(e) => set('phone', e.target.value)} placeholder="+60…" /></label>
            </div>
            {existing && (
              <label className="field">
                {t('req.status')}
                <select value={input.status} onChange={(e) => set('status', e.target.value as 'ACTIVE' | 'DISABLED')} disabled={isSelf}>
                  <option value="ACTIVE">{t('users.statusActive')}</option>
                  <option value="DISABLED">{t('users.statusDisabled')}</option>
                </select>
                {isSelf && <span className="hint">{t('users.notSelf')}</span>}
              </label>
            )}
          </fieldset>

          <fieldset>
            <legend>{t('users.approvers')}</legend>
            <p className="hint">{t('users.approversHint')}</p>
            <div className="field-row">
              <label className="field">
                {t('common.manager')}
                <select value={input.managerId ?? ''} onChange={(e) => set('managerId', e.target.value || null)}>
                  <option value="">{t('users.noManager')}</option>
                  {approvers.map((p) => <option key={p.employeeId} value={p.employeeId!}>{p.fullName}</option>)}
                </select>
              </label>
              <label className="field">
                {t('users.backup')}
                <select value={input.backupApproverId ?? ''} onChange={(e) => set('backupApproverId', e.target.value || null)}>
                  <option value="">{t('users.none')}</option>
                  {approvers.map((p) => <option key={p.employeeId} value={p.employeeId!}>{p.fullName}</option>)}
                </select>
              </label>
            </div>
          </fieldset>

          <fieldset>
            <legend>{t('users.roles')}</legend>
            <p className="hint">{t('users.rolesHint')}</p>
            <div className="role-options">
              {roles.map((r) => (
                <label key={r.id} className="check">
                  <input type="checkbox" checked={input.roleIds.includes(r.id)} onChange={() => toggleRole(r.id)} />
                  <span><strong>{roleText(r).name}</strong>{roleText(r).description && <small>{roleText(r).description}</small>}</span>
                </label>
              ))}
            </div>
          </fieldset>

          <div className="panel-actions">
            <button type="submit" className="btn primary" disabled={save.isPending}>
              {save.isPending ? t('common.saving') : existing ? t('common.saveChanges') : t('users.add')}
            </button>
            {existing && (
              <button type="button" className="btn" onClick={() => reset.mutate()} disabled={reset.isPending}>
                {reset.isPending ? t('users.resetting') : t('users.reset')}
              </button>
            )}
          </div>
        </form>
      )}
    </Panel>
  );
}
