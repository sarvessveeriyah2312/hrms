import { useTranslation } from 'react-i18next';
import { SCOPE_LABEL, useAccess } from '../lib/access';
import { moduleMeta } from '../lib/modules';
import { Glyph } from '../components/Glyph';
import { Card, EmptyState, List, PageHeader, Row, Status } from '../components/app/kit';

const ACTIONS = ['clock', 'apply', 'submit', 'view', 'approve', 'manage'] as const;

/** A module that isn't built yet: says so plainly, and shows what the user will be able to do in it. */
export function PlaceholderPage({ module }: { module: string }) {
  const { t } = useTranslation();
  const { user } = useAccess();
  const meta = moduleMeta(module);
  const mine = Object.entries(user?.scopes ?? {}).filter(([perm]) => perm.startsWith(`${module}:`));

  return (
    <>
      <PageHeader title={meta.label} subtitle={t('placeholder.plan', { company: user?.companyName ?? t('placeholder.yourCompany') })} />
      <div className="grid grid--main">
        <Card>
          <EmptyState icon={meta.icon} tone={meta.tone} title={t('placeholder.notBuilt', { module: meta.label })}>
            {t('placeholder.notBuiltBody')}
          </EmptyState>
        </Card>
        <Card title={t('placeholder.canDo')} count={mine.length}>
          <List>
            {mine.map(([perm, scope]) => (
              <Row
                key={perm}
                lead={<Glyph icon={meta.icon} tone={meta.tone} />}
                title={(() => {
                  const action = perm.split(':')[1] as (typeof ACTIONS)[number];
                  return ACTIONS.includes(action) ? t(`placeholder.actions.${action}`) : perm;
                })()}
                trail={<Status tone="blue">{SCOPE_LABEL[scope]}</Status>}
              />
            ))}
          </List>
        </Card>
      </div>
    </>
  );
}
