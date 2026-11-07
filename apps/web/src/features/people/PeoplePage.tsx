import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api } from '../../lib/api';
import { SCOPE_LABEL, errorText } from '../../lib/access';
import { Avatar, Card, List, PageHeader, Row, Status } from '../../components/app/kit';

/** Employee directory. The API filters it by the scope of your "See employee records" permission. */
export function PeoplePage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const directory = useQuery({ queryKey: ['employees', q], queryFn: () => api.employees(q) });

  return (
    <>
      <PageHeader
        title={t('nav.people')}
        subtitle={directory.data && (
          <>
            <Status tone="blue">{SCOPE_LABEL[directory.data.scope]}</Status>
            {t(`people.scopeNote.${directory.data.scope}`)}
          </>
        )}
      />

      {directory.isError && <p className="form-error" role="alert">{errorText(directory.error)}</p>}

      <Card
        title={t('people.directory')}
        count={directory.data?.employees.length}
        action={
          <input
            className="search-input"
            type="search"
            value={q}
            onChange={(e) => setParams(e.target.value ? { q: e.target.value } : {}, { replace: true })}
            placeholder={t('people.search')}
            aria-label={t('shell.searchPeople')}
          />
        }
      >
        <List>
          {directory.data?.employees.map((e) => (
            <Row
              key={e.id}
              lead={<Avatar name={e.fullName} size={36} />}
              title={e.fullName}
              meta={[e.employeeNo, e.jobTitle, e.department, e.manager && t('dashboard.reportsTo', { name: e.manager })].filter(Boolean).join(' · ')}
              to={`/app/people/${e.id}`}
              trail={<Status tone={e.status === 'ACTIVE' ? 'green' : 'grey'}>{e.status === 'ACTIVE' ? t('common.active') : t('common.inactive')}</Status>}
            />
          ))}
          {directory.data && directory.data.employees.length === 0 && (
            <Row title={q ? t('people.noMatch', { q }) : t('people.noOne')} meta={q ? t('people.tryPart') : undefined} />
          )}
          {directory.isPending && <Row title={t('common.loading')} />}
        </List>
      </Card>
    </>
  );
}
