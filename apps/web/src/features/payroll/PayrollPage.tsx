import { useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { useAccess } from '../../lib/access';
import { PageHeader, Tabs } from '../../components/app/kit';
import { PayrollDashboardTab } from './PayrollDashboardTab';
import { PeriodDocuments } from './PeriodDocuments';
import { PayrollExportTab } from './PayrollExportTab';
import './payroll.css';

/** Payroll for HR: dashboard (payroll:dashboard), payslips and tax forms (payroll:manage), export (payroll:export). */
export function PayrollPage() {
  const { t } = useTranslation();
  const { can } = useAccess();
  const [params, setParams] = useSearchParams();
  const tabs = [
    ...(can('payroll:dashboard') ? [{ key: 'dashboard', label: t('payroll.tabs.dashboard') }] : []),
    ...(can('payroll:manage') ? [{ key: 'payslips', label: t('payroll.tabs.payslips') }, { key: 'tax', label: t('payroll.tabs.taxForms') }] : []),
    ...(can('payroll:export') ? [{ key: 'export', label: t('payroll.tabs.export') }] : []),
  ];
  const tab = tabs.find((x) => x.key === params.get('tab'))?.key ?? tabs[0]?.key ?? 'dashboard';
  const setTab = (key: string) => setParams({ tab: key }, { replace: true });

  return (
    <>
      <PageHeader title={t('nav.payroll')} subtitle={t('payroll.subtitle')} />
      <Tabs tabs={tabs} value={tab} onChange={setTab} label={t('nav.payroll')} titled>
        {tab === 'dashboard' && <PayrollDashboardTab />}
        {tab === 'payslips' && <PeriodDocuments kind="PAYSLIP" />}
        {tab === 'tax' && <PeriodDocuments kind="TAX_FORM" />}
        {tab === 'export' && <PayrollExportTab />}
      </Tabs>
    </>
  );
}
