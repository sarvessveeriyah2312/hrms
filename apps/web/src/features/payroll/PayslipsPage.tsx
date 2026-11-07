import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { PayrollDocument } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { formatMoney } from '../../lib/money';
import { relative } from '../../lib/dates';
import { saveBlob } from '../../lib/download';
import { Card, EmptyState, List, PageHeader, Row, Tabs } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Glyph } from '../../components/Glyph';
import { openBlob, periodLabel } from './payrollText';
import { PayLines } from './PayLines';
import './payroll.css';

/** Your own payslips and tax forms, once HR publishes them. Opening one tells HR you've read it. */
export function PayslipsPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') === 'tax' ? 'tax' : 'payslips';
  const mine = useQuery({ queryKey: ['payroll', 'mine'], queryFn: () => api.payroll.mine() });
  const [open, setOpen] = useState<PayrollDocument | null>(null);
  const payslips = mine.data?.filter((d) => d.kind === 'PAYSLIP') ?? [];
  const forms = mine.data?.filter((d) => d.kind === 'TAX_FORM') ?? [];
  const list = tab === 'tax' ? forms : payslips;

  return (
    <>
      <PageHeader title={t('nav.payslips')} subtitle={t('payroll.mine.subtitle')} />
      {mine.isError && <p className="form-error" role="alert">{errorText(mine.error)}</p>}
      <Tabs tabs={[
        { key: 'payslips', label: t('payroll.tabs.payslips'), count: payslips.length },
        { key: 'tax', label: t('payroll.tabs.taxForms'), count: forms.length },
      ]} value={tab} onChange={(k) => setParams(k === 'tax' ? { tab: 'tax' } : {}, { replace: true })} label={t('nav.payslips')} titled>
        {mine.data && list.length === 0 ? (
          <EmptyState icon="wallet" tone="pink" title={tab === 'tax' ? t('payroll.mine.noForms') : t('payroll.mine.noPayslips')}>
            {t('payroll.mine.noneHint')}
          </EmptyState>
        ) : (
          <Card>
            <List>
              {list.map((d) => (
                <Row key={d.id} lead={<Glyph icon={d.kind === 'PAYSLIP' ? 'wallet' : 'file'} tone="pink" />}
                  title={periodLabel(d)}
                  meta={[d.publishedAt && t('payroll.mine.published', { when: relative(d.publishedAt) }), !d.firstViewedAt && t('payroll.mine.new')].filter(Boolean).join(' · ')}
                  trail={d.net != null && d.currency ? <strong className="num">{formatMoney(d.net, d.currency)}</strong> : undefined}
                  onClick={() => setOpen(d)} />
              ))}
              {mine.isPending && <Row title={t('common.loading')} />}
            </List>
          </Card>
        )}
      </Tabs>
      {open && <DocumentPanel doc={open} onClose={() => setOpen(null)} />}
    </>
  );
}

function DocumentPanel({ doc, onClose }: { doc: PayrollDocument; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const detail = useQuery({ queryKey: ['payroll', 'doc', doc.id], queryFn: () => api.payroll.detail(doc.id) });
  const [password, setPassword] = useState('');
  const [sharing, setSharing] = useState(false);
  const [opening, setOpening] = useState<string | null>(null);
  const read = () => queryClient.invalidateQueries({ queryKey: ['payroll', 'mine'] });
  const protect = useMutation({
    mutationFn: () => api.payroll.protectedCopy(doc.id, password),
    onSuccess: (blob) => {
      saveBlob(blob, `${(doc.fileName ?? 'payslip.pdf').replace(/\.pdf$/i, '')} (protected).pdf`);
      setPassword('');
      setSharing(false);
      void read();
    },
  });
  const lines = detail.data?.lines ?? [];

  return (
    <Panel title={periodLabel(doc)} onClose={onClose}>
      {detail.error && <p className="form-error" role="alert">{errorText(detail.error)}</p>}
      {lines.length > 0 && doc.currency && <PayLines lines={lines} currency={doc.currency} />}
      {lines.length === 0 && doc.kind === 'PAYSLIP' && <p className="hint">{t('payroll.mine.pdfOnly')}</p>}
      {opening && <p className="form-error" role="alert">{opening}</p>}
      <div className="panel-actions">
        <button type="button" className="btn primary" onClick={() => openBlob(() => api.payroll.file(doc.id), setOpening).then(read)}>
          {t('payroll.mine.openPdf')}
        </button>
        <button type="button" className="btn" onClick={() => setSharing((s) => !s)} aria-expanded={sharing}>{t('payroll.mine.share')}</button>
      </div>
      {sharing && (
        <form className="stack payroll-share" onSubmit={(e) => { e.preventDefault(); protect.mutate(); }}>
          <p className="hint">{t('payroll.mine.shareHint')}</p>
          {protect.error && <p className="form-error" role="alert">{errorText(protect.error)}</p>}
          <label className="field">
            {t('payroll.mine.password')}
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} minLength={6} maxLength={64} required autoComplete="new-password" />
          </label>
          <div className="panel-actions">
            <button type="submit" className="btn primary" disabled={password.length < 6 || protect.isPending}>{t('payroll.mine.downloadProtected')}</button>
          </div>
        </form>
      )}
    </Panel>
  );
}
