import { useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { PayrollDocument, PayrollKind, PayrollUploadResult, TaxFormCode } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { formatDate, relative } from '../../lib/dates';
import { formatMoney } from '../../lib/money';
import { Card, List, Row, Status, StatCard } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Table } from '../../components/app/Table';
import { formName, openBlob, periodLabel } from './payrollText';
import { TemplateCard, TemplateSelect } from './PayslipTemplates';
import { templateText, usePayslipTemplates } from './templateText';

const FORMS: TaxFormCode[] = ['EA', 'IR8A', 'IR56B', 'FORM16', 'OTHER'];
const SAMPLE = 'employee_no,type,item,amount,quantity,rate\nE0001,BASIC,Basic pay,3000.00,26,3000\nE0001,ALLOWANCE,Meal,93.00,,\nE0001,ALLOWANCE,Night shift allowance,10.00,,\nE0001,OVERTIME,Normal OT,86.54,4,21.64\nE0001,DEDUCTION,Employee EPF,319.00,,\nE0001,DEDUCTION,Employee SOCSO,19.25,,\nE0001,DEDUCTION,Employee PCB,12.40,,\nE0001,EMPLOYER,Employer EPF,377.00,,\nE0001,EMPLOYER,Employer SOCSO,67.35,,\n';

const currentYear = () => new Date().getFullYear();

function lastMonth() {
  const d = new Date();
  d.setDate(1);
  d.setMonth(d.getMonth() - 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

/**
 * One pay period's payslips (or one tax year's forms): upload PDFs (matched by employee number in the file name),
 * generate payslips from pay lines, publish the drafts, and see who has opened theirs. The period is kept in the URL.
 */
export function PeriodDocuments({ kind }: { kind: PayrollKind }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const payslip = kind === 'PAYSLIP';
  const period = params.get('period') ?? (payslip ? lastMonth() : String(currentYear() - 1));
  const formCode = (params.get('form') as TaxFormCode | null) ?? 'EA';
  const setParam = (k: string, v: string) => setParams((p) => { const n = new URLSearchParams(p); n.set(k, v); return n; }, { replace: true });

  const view = useQuery({
    queryKey: ['payroll', 'period', kind, period, payslip ? null : formCode],
    queryFn: () => api.payroll.period(kind, period, payslip ? null : formCode),
    enabled: payslip ? /^\d{4}-\d{2}$/.test(period) : /^\d{4}$/.test(period),
  });
  const [generating, setGenerating] = useState(false);
  const [redrawing, setRedrawing] = useState(false);
  const [result, setResult] = useState<PayrollUploadResult | null>(null);
  const [opening, setOpening] = useState<string | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['payroll'] });

  const docs = view.data?.documents ?? [];
  const drafts = docs.filter((d) => d.status === 'DRAFT');
  const published = docs.filter((d) => d.status === 'PUBLISHED');
  const read = published.filter((d) => d.firstViewedAt);
  const label = periodLabel({ kind, period, formCode: payslip ? null : formCode });
  const templates = usePayslipTemplates();
  const templateName = (code: string | null) => {
    const tpl = code ? templates.data?.templates.find((x) => x.code === code) : undefined;
    return tpl ? templateText(tpl).name : null;
  };

  const publish = useMutation({
    mutationFn: () => api.payroll.publish({ kind, period, formCode: payslip ? null : formCode }),
    onSuccess: async () => { setResult(null); await refresh(); },
  });
  const act = useMutation({
    mutationFn: ({ doc, action }: { doc: PayrollDocument; action: 'unpublish' | 'remove' }) =>
      action === 'unpublish' ? api.payroll.unpublish(doc.id) : api.payroll.remove(doc.id),
    onSuccess: refresh,
  });

  return (
    <div className="stack">
      <div className="payroll-toolbar">
        <label className="field">
          {payslip ? t('payroll.period.month') : t('payroll.period.year')}
          {payslip
            ? <input type="month" value={period} onChange={(e) => e.target.value && setParam('period', e.target.value)} />
            : <input type="number" min={2000} max={2100} value={period} onChange={(e) => setParam('period', e.target.value)} />}
        </label>
        {!payslip && (
          <label className="field">
            {t('payroll.period.form')}
            <select value={formCode} onChange={(e) => setParam('form', e.target.value)}>
              {FORMS.map((f) => <option key={f} value={f}>{formName(f)}</option>)}
            </select>
          </label>
        )}
        <div className="panel-actions payroll-toolbar__actions">
          <UploadButton kind={kind} period={period} formCode={payslip ? null : formCode} onDone={async (r) => { setResult(r); await refresh(); }} />
          {payslip && <button type="button" className="btn" onClick={() => setGenerating(true)}>{t('payroll.generate.button')}</button>}
          {payslip && docs.some((d) => d.source === 'GENERATED') && (
            <button type="button" className="btn" onClick={() => setRedrawing(true)}>{t('payroll.redraw.button')}</button>
          )}
          <button type="button" className="btn primary" disabled={drafts.length === 0 || publish.isPending} onClick={() => publish.mutate()}>
            {t('payroll.period.publish', { count: drafts.length })}
          </button>
        </div>
      </div>

      {view.isError && <p className="form-error" role="alert">{errorText(view.error)}</p>}
      {publish.error && <p className="form-error" role="alert">{errorText(publish.error)}</p>}
      {act.error && <p className="form-error" role="alert">{errorText(act.error)}</p>}
      {opening && <p className="form-error" role="alert">{opening}</p>}
      {publish.data && <p className="payroll-done" role="status">{t('payroll.period.publishedNow', { count: publish.data.published })}</p>}
      {result && <UploadSummary result={result} onClose={() => setResult(null)} />}

      <div className="grid grid--stats">
        <StatCard icon="file" label={t('payroll.period.documents')} value={view.data ? docs.length : '–'} note={label} />
        <StatCard icon="lock" label={t('payroll.period.drafts')} value={view.data ? drafts.length : '–'} note={t('payroll.period.draftsNote')} />
        <StatCard icon="check" label={t('payroll.period.published')} value={view.data ? published.length : '–'}
          note={t('payroll.period.readOf', { read: read.length, total: published.length })} />
        <StatCard icon="users" label={t('payroll.period.missing')} value={view.data ? view.data.missing.length : '–'} note={t('payroll.period.missingNote')} />
      </div>

      {payslip && <TemplateCard />}

      <Card title={label} count={docs.length}>
        <Table
          rows={docs}
          rowKey={(d) => d.id}
          label={label}
          loading={view.isPending}
          empty={payslip ? t('payroll.period.emptyPayslips') : t('payroll.period.emptyForms')}
          resetKey={`${kind}-${period}-${formCode}`}
          columns={[
            { key: 'person', header: t('payroll.col.person'), rowHeader: true,
              cell: (d) => <>{d.employeeName}<small>{[d.employeeNo, d.department].filter(Boolean).join(' · ')}</small></> },
            { key: 'status', header: t('payroll.col.status'),
              cell: (d) => d.status === 'DRAFT' ? <Status tone="grey">{t('payroll.status.DRAFT')}</Status> : <Status tone="blue">{t('payroll.status.PUBLISHED')}</Status> },
            { key: 'read', header: t('payroll.col.read'),
              cell: (d) => d.status !== 'PUBLISHED' ? '—' : d.firstViewedAt
                ? <Status tone="green">{t('payroll.read.yes', { when: formatDate(d.firstViewedAt, { day: 'numeric', month: 'short' }) })}</Status>
                : <Status tone="amber">{t('payroll.read.no')}</Status> },
            ...(payslip ? [{ key: 'net', header: t('payroll.col.net'), align: 'end' as const,
              cell: (d: PayrollDocument) => d.net != null && d.currency ? formatMoney(d.net, d.currency) : '—' }] : []),
            { key: 'source', header: t('payroll.col.source'),
              cell: (d) => <>{d.source === 'GENERATED' ? t('payroll.source.GENERATED') : t('payroll.source.UPLOAD')}<small>{[templateName(d.template), relative(d.createdAt)].filter(Boolean).join(' · ')}</small></> },
            { key: 'actions', header: <span className="sr-only">{t('payroll.col.actions')}</span>, align: 'end',
              cell: (d) => (
                <span className="payroll-row-actions">
                  <button type="button" className="btn small" onClick={() => void openBlob(() => api.payroll.file(d.id), setOpening)}>{t('files.open')}</button>
                  {d.status === 'PUBLISHED'
                    ? <button type="button" className="btn small" disabled={act.isPending} onClick={() => act.mutate({ doc: d, action: 'unpublish' })}>{t('payroll.period.withdraw')}</button>
                    : <button type="button" className="btn small reject" disabled={act.isPending} onClick={() => act.mutate({ doc: d, action: 'remove' })}>{t('common.delete')}</button>}
                </span>
              ) },
          ]}
        />
      </Card>

      {view.data && view.data.missing.length > 0 && (
        <Card title={payslip ? t('payroll.period.noPayslip') : t('payroll.period.noForm')} count={view.data.missing.length}>
          <List>
            {view.data.missing.slice(0, 50).map((m) => (
              <Row key={m.employeeId} title={m.employeeName} meta={[m.employeeNo, m.department].filter(Boolean).join(' · ')} />
            ))}
          </List>
          {view.data.missing.length > 50 && <p className="hint payroll-more">{t('payroll.period.andMore', { count: view.data.missing.length - 50 })}</p>}
        </Card>
      )}

      {redrawing && (
        <RedrawPanel period={period} label={label} count={docs.filter((d) => d.source === 'GENERATED').length}
          published={docs.filter((d) => d.source === 'GENERATED' && d.status === 'PUBLISHED').length}
          onClose={() => setRedrawing(false)} onDone={async (r) => { setResult(r); setRedrawing(false); await refresh(); }} />
      )}
      {generating && <GeneratePanel period={period} onClose={() => setGenerating(false)} onDone={async (r) => { setResult(r); setGenerating(false); await refresh(); }} />}
    </div>
  );
}

/** Picks PDFs, uploads each, then attaches them to the period; the server matches people by employee number. */
function UploadButton({ kind, period, formCode, onDone }: {
  kind: PayrollKind; period: string; formCode: TaxFormCode | null; onDone: (r: PayrollUploadResult) => void;
}) {
  const { t } = useTranslation();
  const input = useRef<HTMLInputElement>(null);
  const [progress, setProgress] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const run = async (files: File[]) => {
    setError(null);
    const failed: PayrollUploadResult['failed'] = [];
    const ids: string[] = [];
    for (let i = 0; i < files.length; i++) {
      setProgress(t('payroll.upload.progress', { n: i + 1, total: files.length }));
      const f = files[i];
      if (f.size > 5 * 1024 * 1024) { failed.push({ fileId: null, name: f.name, message: t('files.tooBig') }); continue; }
      try {
        ids.push((await api.files.upload(f)).id);
      } catch (e) {
        failed.push({ fileId: null, name: f.name, message: errorText(e) });
      }
    }
    try {
      const result = ids.length ? await api.payroll.upload({ kind, period, formCode, items: ids.map((fileId) => ({ fileId })) }) : { saved: [], failed: [] };
      onDone({ saved: result.saved, failed: [...failed, ...result.failed] });
    } catch (e) {
      setError(errorText(e));
    } finally {
      setProgress(null);
    }
  };

  return (
    <>
      <button type="button" className="btn" onClick={() => input.current?.click()} disabled={!!progress} title={t('payroll.upload.hint')}>
        {progress ?? t('payroll.upload.button')}
      </button>
      <input ref={input} type="file" accept="application/pdf,.pdf" multiple hidden aria-label={t('payroll.upload.button')}
        onChange={(e) => { const files = [...(e.target.files ?? [])]; e.target.value = ''; if (files.length) void run(files.slice(0, 500)); }} />
      {error && <p className="form-error" role="alert">{error}</p>}
    </>
  );
}

function UploadSummary({ result, onClose }: { result: PayrollUploadResult; onClose: () => void }) {
  const { t } = useTranslation();
  return (
    <div className={result.failed.length ? 'form-error payroll-result' : 'payroll-done payroll-result'} role="status">
      <p>{t('payroll.upload.saved', { count: result.saved.length })}{result.failed.length > 0 && ` ${t('payroll.upload.failed', { count: result.failed.length })}`}</p>
      {result.failed.length > 0 && <ul>{result.failed.map((f, n) => <li key={n}><strong>{f.name ?? '—'}</strong>: {f.message}</li>)}</ul>}
      <button type="button" className="link-button" onClick={onClose}>{t('common.close')}</button>
    </div>
  );
}

/** Generates payslips from a CSV of pay lines exported from the payroll software. Checked before anything is made. */
function GeneratePanel({ period, onClose, onDone }: { period: string; onClose: () => void; onDone: (r: PayrollUploadResult) => void }) {
  const { t } = useTranslation();
  const [csv, setCsv] = useState<string | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);
  const [template, setTemplate] = useState<string | null>(null);
  const generate = useMutation({ mutationFn: () => api.payroll.generate(period, csv ?? '', template ?? undefined), onSuccess: onDone });
  const problems = (generate.error as { problem?: { errors?: string[] } } | null)?.problem?.errors ?? [];
  const lineCount = csv ? csv.split(/\r?\n/).filter((l) => l.trim()).length - 1 : 0;

  return (
    <Panel title={t('payroll.generate.title', { month: periodLabel({ kind: 'PAYSLIP', period, formCode: null }) })} onClose={onClose}>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); generate.mutate(); }}>
        <p className="hint">{t('payroll.generate.intro')}</p>
        <pre className="payroll-sample" aria-label={t('payroll.generate.sampleLabel')}>{SAMPLE}</pre>
        <p className="hint">{t('payroll.generate.types')}</p>
        <button type="button" className="link-button" onClick={() => {
          const url = URL.createObjectURL(new Blob([SAMPLE], { type: 'text/csv' }));
          const a = document.createElement('a');
          a.href = url;
          a.download = 'pay-lines-sample.csv';
          a.click();
          setTimeout(() => URL.revokeObjectURL(url), 10_000);
        }}>{t('payroll.generate.downloadSample')}</button>
        <label className="field">
          {t('payroll.generate.file')}
          <input type="file" accept=".csv,text/csv" onChange={async (e) => {
            const f = e.target.files?.[0];
            generate.reset();
            if (!f) return;
            if (f.size > 1024 * 1024) { setCsv(null); setFileName(null); return; }
            setFileName(f.name);
            setCsv(await f.text());
          }} />
        </label>
        {fileName && <p className="hint">{t('payroll.generate.loaded', { name: fileName, count: Math.max(0, lineCount) })}</p>}
        {generate.error && (
          <div className="form-error" role="alert">
            <p>{errorText(generate.error)}</p>
            {problems.length > 0 && <ul>{problems.map((p, n) => <li key={n}>{p}</li>)}</ul>}
          </div>
        )}
        <TemplateSelect value={template} onChange={setTemplate} />
        <p className="hint">{t('payroll.generate.fromProfile')}</p>
        <p className="hint">{t('payroll.generate.drafts')}</p>
        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={!csv || generate.isPending}>
            {generate.isPending ? t('payroll.generate.working') : t('payroll.generate.go')}
          </button>
          <button type="button" className="btn" onClick={onClose}>{t('common.cancel')}</button>
        </div>
      </form>
    </Panel>
  );
}

/** Draws the month's generated payslips again in another template, from their saved pay lines. */
function RedrawPanel({ period, label, count, published, onClose, onDone }: {
  period: string; label: string; count: number; published: number; onClose: () => void; onDone: (r: PayrollUploadResult) => void;
}) {
  const { t } = useTranslation();
  const [template, setTemplate] = useState<string | null>(null);
  const redraw = useMutation({ mutationFn: () => api.payroll.redraw(period, template ?? undefined), onSuccess: onDone });
  return (
    <Panel title={t('payroll.redraw.title', { month: label })} onClose={onClose}>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); redraw.mutate(); }}>
        <p className="hint">{t('payroll.redraw.intro', { count })}</p>
        {published > 0 && <p className="hint"><strong>{t('payroll.redraw.published', { count: published })}</strong></p>}
        {redraw.error && <p className="form-error" role="alert">{errorText(redraw.error)}</p>}
        <TemplateSelect value={template} onChange={setTemplate} />
        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={redraw.isPending}>
            {redraw.isPending ? t('payroll.redraw.working') : t('payroll.redraw.go', { count })}
          </button>
          <button type="button" className="btn" onClick={onClose}>{t('common.cancel')}</button>
        </div>
      </form>
    </Panel>
  );
}
