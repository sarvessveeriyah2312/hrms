import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { CompanySettings } from '@hrms/api-client';
import { api, reloadSession } from '../../lib/api';
import { errorText } from '../../lib/access';
import { moduleMeta, moduleName } from '../../lib/modules';
import { Card, PageHeader, Status, Tabs, Tile } from '../../components/app/kit';
import { DepartmentsTab } from './DepartmentsTab';
import { MailTab } from './MailTab';
import './admin.css';

const TABS = ['details', 'modules', 'departments', 'email'] as const;
type Tab = (typeof TABS)[number];

/** Company settings in tabs: details (name and sign-in code), modules, departments, and email (SMTP server). */
export function CompanyPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const company = useQuery({ queryKey: ['admin', 'company'], queryFn: api.admin.company });
  const departments = useQuery({ queryKey: ['admin', 'departments'], queryFn: api.admin.departments });
  const [params, setParams] = useSearchParams();
  const tab: Tab = (TABS as readonly string[]).includes(params.get('tab') ?? '') ? (params.get('tab') as Tab) : 'details';

  const toggle = useMutation({
    mutationFn: (m: { key: string; enabled: boolean }) => api.admin.setModule(m.key, m.enabled),
    onSuccess: async (data) => {
      queryClient.setQueryData(['admin', 'company'], data);
      await reloadSession(); // menus and permissions follow the modules
    },
  });
  const onCount = company.data?.modules.filter((m) => m.enabled).length ?? 0;
  const name = company.data?.name ?? t('placeholder.yourCompany');
  const subtitle = tab === 'details' ? t('company.subtitle.details', { name }) : t(`company.subtitle.${tab}`);

  return (
    <>
      <PageHeader title={t('nav.company')} subtitle={subtitle} />
      {company.isError && <p className="form-error" role="alert">{errorText(company.error)}</p>}

      <Tabs titled
        label={t('company.settings')}
        value={tab}
        onChange={(key) => setParams(key === 'details' ? {} : { tab: key }, { replace: true })}
        tabs={[
          { key: 'details', label: t('company.details'), icon: 'building' },
          { key: 'modules', label: t('shell.modules'), icon: 'sliders', count: company.data ? onCount : undefined },
          { key: 'departments', label: t('users.departments'), icon: 'users', count: departments.data?.length },
          { key: 'email', label: t('mail.tab'), icon: 'message' },
        ]}
      >
        {tab === 'details' && company.data && (
          <div className="company-details">
            <DetailsForm initial={company.data} />
            <Card title={t('company.glance')} padded>
              <dl className="facts">
                <dt>{t('auth.companyCode')}</dt><dd><code className="code-chip">{company.data.code}</code></dd>
                <dt>{t('landing.country')}</dt><dd>{company.data.country}</dd>
                <dt>{t('company.modulesOn')}</dt><dd>{t('company.nOf', { n: onCount, total: company.data.modules.length })}</dd>
                <dt>{t('users.departments')}</dt><dd>{departments.data?.length ?? '–'}</dd>
              </dl>
            </Card>
          </div>
        )}

        {tab === 'modules' && (
          <Card title={t('shell.modules')} count={onCount}>
            <p className="muted card-inset">{t('company.modulesHint')}</p>
            {toggle.error && <p className="form-error card-inset" role="alert">{errorText(toggle.error)}</p>}
            <div className="tiles tiles--wide">
              {company.data?.modules.map((m) => {
                const meta = moduleMeta(m.key);
                return (
                  <Tile
                    key={m.key}
                    icon={meta.icon}
                    tone={meta.tone}
                    name={moduleName(m.key, m.name)}
                    pressed={m.enabled}
                    onClick={() => !toggle.isPending && toggle.mutate({ key: m.key, enabled: !m.enabled })}
                    status={<Status tone={m.enabled ? 'blue' : 'grey'}>{m.enabled ? t('common.on') : t('common.off')}</Status>}
                  />
                );
              })}
            </div>
          </Card>
        )}

        {tab === 'departments' && <DepartmentsTab />}
        {tab === 'email' && <MailTab />}
      </Tabs>
    </>
  );
}

function DetailsForm({ initial }: { initial: CompanySettings }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [details, setDetails] = useState({ name: initial.name, code: initial.code });
  const [saved, setSaved] = useState(false);
  const save = useMutation({
    mutationFn: () => api.admin.updateCompany(details),
    onSuccess: async (data) => {
      queryClient.setQueryData(['admin', 'company'], data);
      await reloadSession(); // company name and code are in the session
      setSaved(true);
    },
  });

  return (
    <Card title={t('company.details')} padded>
      <form
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          setSaved(false);
          save.mutate();
        }}
      >
        {save.error && <p className="form-error" role="alert">{errorText(save.error)}</p>}
        <div className="field-row">
          <label className="field">{t('company.name')}<input value={details.name} onChange={(e) => setDetails({ ...details, name: e.target.value })} required /></label>
          <label className="field">
            {t('auth.companyCode')}
            <input value={details.code} onChange={(e) => setDetails({ ...details, code: e.target.value.toUpperCase() })} required pattern="[A-Za-z0-9-]{3,20}" />
          </label>
        </div>
        <p className="hint">{t('company.codeHint')}</p>
        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={save.isPending}>{save.isPending ? t('common.saving') : t('company.save')}</button>
          {saved && <span className="hint" role="status">{t('ww.saved')}</span>}
        </div>
      </form>
    </Card>
  );
}
