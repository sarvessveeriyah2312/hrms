import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { useOpenParam } from '../../lib/openParam';
import { useTranslation } from 'react-i18next';
import type { AdminRole, AdminRoleInput, CatalogModule, DataScope } from '@hrms/api-client';
import { api, reloadSession } from '../../lib/api';
import { SCOPE_LABEL, errorText, useAccess } from '../../lib/access';
import { moduleMeta, moduleName, permText, roleText } from '../../lib/modules';
import { Glyph } from '../../components/Glyph';
import { Icon } from '../../components/Icon';
import { Card, List, PageHeader, Status, Tabs } from '../../components/app/kit';
import { Table } from '../../components/app/Table';
import './admin.css';

const LOCKED = 'SUPER_ADMIN';
const PAGE_TABS = ['roles', 'compare'] as const;
type PageTab = (typeof PAGE_TABS)[number];

/** Roles and what each one allows: one row per action, with how far it reaches (own records → whole company). */
export function RolesPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const roles = useQuery({ queryKey: ['admin', 'roles'], queryFn: api.admin.roles });
  const catalog = useQuery({ queryKey: ['admin', 'catalog'], queryFn: api.admin.catalog, staleTime: Infinity });
  const [picked, setSelected] = useState<string | 'new' | null>(() => (params.get('new') ? 'new' : null));
  // Opened from search: /app/admin/roles?open=<role id>
  const [openId] = useOpenParam();
  const selected = openId ?? picked;
  const current = selected === 'new' ? null : roles.data?.find((r) => r.id === selected) ?? roles.data?.[0] ?? null;

  const tab: PageTab = (PAGE_TABS as readonly string[]).includes(params.get('tab') ?? '') ? (params.get('tab') as PageTab) : 'roles';
  const select = (id: string | 'new' | null) => {
    setSelected(id);
    if (params.get('new') || params.get('tab') || params.get('open')) setParams({}, { replace: true });
  };

  return (
    <>
      <PageHeader
        title={t('nav.roles')}
        subtitle={tab === 'roles' ? t('roles.subtitle') : t('roles.compareSubtitle')}
        actions={tab === 'roles' && <button type="button" className="btn primary" onClick={() => select('new')}><Icon name="plus" size={16} />{t('roles.new')}</button>}
      />

      {(roles.isError || catalog.isError) && <p className="form-error" role="alert">{errorText(roles.error ?? catalog.error)}</p>}

      <Tabs titled
        label={t('nav.roles')}
        value={tab}
        onChange={(key) => setParams(key === 'roles' ? {} : { tab: key }, { replace: true })}
        tabs={[
          { key: 'roles', label: t('users.roles'), icon: 'shield', count: roles.data?.length },
          { key: 'compare', label: t('roles.compare'), icon: 'sliders' },
        ]}
      >
      {tab === 'compare' && roles.data && catalog.data && <CompareRoles roles={roles.data} catalog={catalog.data} onOpen={(id) => select(id)} />}
      {tab === 'roles' && (
      <div className="roles-layout">
        <Card title={t('users.roles')} count={roles.data?.length}>
          <List>
            {roles.data?.map((r) => {
              const isCurrent = selected !== 'new' && current?.id === r.id;
              return (
                <li key={r.id}>
                  <button type="button" className={isCurrent ? 'ui-row ui-row--link is-selected' : 'ui-row ui-row--link'} aria-current={isCurrent} onClick={() => select(r.id)}>
                    <span className="ui-row__lead"><Glyph icon={r.code === LOCKED ? 'lock' : 'shield'} tone={r.system ? 'pink' : 'purple'} /></span>
                    <span className="ui-row__text">
                      <span className="ui-row__title">{roleText(r).name}</span>
                      <span className="ui-row__meta">{[t('common.person', { count: r.userCount }), t('roles.nPerms', { count: Object.keys(r.permissions).length }), r.system && t('roles.builtIn')].filter(Boolean).join(' · ')}</span>
                    </span>
                  </button>
                </li>
              );
            })}
            {selected === 'new' && (
              <li><span className="ui-row is-selected"><span className="ui-row__lead"><Glyph icon="plus" tone="purple" /></span><span className="ui-row__text"><span className="ui-row__title">{t('roles.new')}</span><span className="ui-row__meta">{t('roles.notSaved')}</span></span></span></li>
            )}
          </List>
        </Card>
        {catalog.data && (selected === 'new' || current) && (
          <RoleEditor
            key={selected === 'new' ? 'new' : current!.id}
            role={selected === 'new' ? null : current}
            catalog={catalog.data}
            onSaved={(id) => select(id)}
            onDeleted={() => select(null)}
          />
        )}
      </div>
      )}
      </Tabs>
    </>
  );
}

type PermRow = { module: CatalogModule; action: CatalogModule['actions'][number] };

/** Read-only matrix: one row per permission, one column per role, showing how far each role reaches. */
function CompareRoles({ roles, catalog, onOpen }: { roles: AdminRole[]; catalog: CatalogModule[]; onOpen: (id: string) => void }) {
  const { t } = useTranslation();
  const access = useAccess();
  const rows: PermRow[] = catalog.flatMap((module) => module.actions.map((action) => ({ module, action })));
  return (
    <Card title={t('roles.compare')} count={roles.length}>
      <p className="muted card-pad">{t('roles.compareHint')}</p>
      <Table
        label={t('roles.compare')}
        className="compare-table"
        rows={rows}
        rowKey={(r) => `${r.module.key}:${r.action.key}`}
        groupOf={(r) => {
          const meta = moduleMeta(r.module.key);
          const off = r.module.licensable && !access.hasModule(r.module.key);
          return {
            key: r.module.key,
            label: <span className="compare-module__label"><Glyph icon={meta.icon} tone={meta.tone} size="sm" />{moduleName(r.module.key, r.module.name)}{off && <Status tone="grey">{t('roles.offForCompany')}</Status>}</span>,
          };
        }}
        columns={[
          {
            key: 'permission', header: t('roles.permission'), rowHeader: true,
            cell: (r) => {
              const text = permText(r.module.key, r.action.key, r.action);
              return <>{text.name}<small>{text.description}</small></>;
            },
          },
          ...roles.map((role) => ({
            key: role.id,
            header: <><button type="button" className="link-button" onClick={() => onOpen(role.id)}>{roleText(role).name}</button><small>{t('common.person', { count: role.userCount })}</small></>,
            cellClass: () => 'compare-cell',
            cell: (r: PermRow) => {
              const scope = role.permissions[`${r.module.key}:${r.action.key}`];
              return scope
                ? <span className={`scope-chip scope-chip--${scope.toLowerCase()}`} title={SCOPE_LABEL[scope]}>{t(`roles.short.${scope}`)}</span>
                : <span className="hint">—</span>;
            },
          })),
        ]}
      />
    </Card>
  );
}

function RoleEditor({ role, catalog, onSaved, onDeleted }: {
  role: AdminRole | null; catalog: CatalogModule[]; onSaved: (id: string) => void; onDeleted: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const access = useAccess();
  const locked = role?.code === LOCKED;
  const [moduleTab, setModuleTab] = useState(catalog[0]?.key ?? '');
  const [input, setInput] = useState<AdminRoleInput>({
    name: role?.name ?? '',
    description: role?.description ?? '',
    permissions: { ...(role?.permissions ?? {}) },
  });

  const setScope = (permission: string, scope: DataScope | '') =>
    setInput((v) => {
      const permissions = { ...v.permissions };
      if (scope) permissions[permission] = scope;
      else delete permissions[permission];
      return { ...v, permissions };
    });

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ['admin'] });
    await reloadSession(); // if you hold this role, your own access just changed
  };

  const save = useMutation({
    mutationFn: () => (role ? api.admin.updateRole(role.id, input) : api.admin.createRole(input)),
    onSuccess: async (saved) => {
      await refresh();
      onSaved(saved.id);
    },
  });
  const remove = useMutation({
    mutationFn: () => api.admin.deleteRole(role!.id),
    onSuccess: async () => {
      await refresh();
      onDeleted();
    },
  });
  const error = save.error ?? remove.error;

  return (
    <Card
      title={role ? roleText(role).name : t('roles.new')}
      action={role && <Status tone={role.system ? 'grey' : 'blue'}>{role.system ? t('roles.builtIn') : t('roles.custom')}</Status>}
    >
      <form
        className="role-editor"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        {error && <p className="form-error" role="alert">{errorText(error)}</p>}
        {locked && <p className="notice">{t('roles.locked')}</p>}

        <div className="field-row">
          <label className="field">{t('roles.name')}<input value={input.name} onChange={(e) => setInput({ ...input, name: e.target.value })} disabled={locked} required /></label>
          <label className="field">{t('roles.description')}<input value={input.description ?? ''} onChange={(e) => setInput({ ...input, description: e.target.value })} disabled={locked} placeholder={t('roles.descriptionHint')} /></label>
        </div>

        <Tabs
          label={t('roles.byModule')}
          value={moduleTab}
          onChange={setModuleTab}
          tabs={catalog.map((m) => ({
            key: m.key,
            label: moduleName(m.key, m.name),
            icon: moduleMeta(m.key).icon,
            count: m.actions.filter((a) => input.permissions[`${m.key}:${a.key}`]).length,
          }))}
        >
          {catalog.filter((m) => m.key === moduleTab).map((m) => {
            const off = m.licensable && !access.hasModule(m.key);
            return (
              <fieldset key={m.key} className="perm-module perm-module--tab">
                <legend className="sr-only">{moduleName(m.key, m.name)}</legend>
                {off && <p className="notice">{t('roles.moduleOff', { module: moduleName(m.key, m.name) })}</p>}
                {m.actions.map((a) => {
                  const permission = `${m.key}:${a.key}`;
                  const value = input.permissions[permission] ?? '';
                  const text = permText(m.key, a.key, a);
                  return (
                    <label key={a.key} className="perm-row">
                      <span>
                        <strong>{text.name}</strong>
                        <small>{text.description}</small>
                      </span>
                      <select
                        value={value}
                        onChange={(e) => setScope(permission, e.target.value as DataScope | '')}
                        disabled={locked}
                        aria-label={t('roles.appliesTo', { name: text.name })}
                      >
                        <option value="">{t('roles.noAccess')}</option>
                        {a.scopes.map((sc) => <option key={sc} value={sc}>{SCOPE_LABEL[sc]}</option>)}
                      </select>
                    </label>
                  );
                })}
              </fieldset>
            );
          })}
        </Tabs>

        {!locked && (
          <div className="panel-actions">
            <button type="submit" className="btn primary" disabled={save.isPending}>
              {save.isPending ? t('common.saving') : role ? t('roles.save') : t('roles.create')}
            </button>
            {role && !role.system && (
              <button
                type="button"
                className="btn reject"
                onClick={() => remove.mutate()}
                disabled={remove.isPending || role.userCount > 0}
                title={role.userCount > 0 ? t('roles.inUse') : undefined}
              >
                {t('roles.delete')}
              </button>
            )}
            <span className="hint">{t('roles.antiEscalation')}</span>
          </div>
        )}
      </form>
    </Card>
  );
}
