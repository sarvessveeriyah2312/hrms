import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { PayslipTemplate } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { Card, Status } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Icon } from '../../components/Icon';
import { openBlob } from './payrollText';
import { templateText, usePayslipTemplates } from './templateText';

function Thumb({ template, size = 'md' }: { template: PayslipTemplate; size?: 'sm' | 'md' }) {
  return template.preview
    ? <img className={`tpl-thumb tpl-thumb--${size}`} src={`/payslip-templates/${template.preview}`} alt="" loading="lazy" />
    : <span className={`tpl-thumb tpl-thumb--${size} tpl-thumb--blank`} aria-hidden="true"><Icon name="file" size={size === 'sm' ? 20 : 28} /></span>;
}

/** The company's default payslip layout, with a gallery to change it and sample PDFs to compare. */
export function TemplateCard() {
  const { t } = useTranslation();
  const templates = usePayslipTemplates();
  const [choosing, setChoosing] = useState(false);
  const current = templates.data?.templates.find((x) => x.code === templates.data?.defaultTemplate);

  return (
    <Card padded title={t('payroll.templates.title')}
      action={<button type="button" className="btn small" onClick={() => setChoosing(true)} disabled={!templates.data}>{t('payroll.templates.change')}</button>}>
      {templates.isError && <p className="form-error" role="alert">{errorText(templates.error)}</p>}
      {current && (
        <div className="tpl-current">
          <Thumb template={current} />
          <div>
            <strong>{templateText(current).name}</strong>
            <p className="hint">{templateText(current).description}</p>
            <p className="hint">{t('payroll.templates.currentHint')}</p>
          </div>
        </div>
      )}
      {choosing && templates.data && <TemplateGallery current={templates.data.defaultTemplate} templates={templates.data.templates} onClose={() => setChoosing(false)} />}
    </Card>
  );
}

function TemplateGallery({ current, templates, onClose }: { current: string; templates: PayslipTemplate[]; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: (code: string) => api.payroll.setDefaultTemplate(code),
    onSuccess: (data) => queryClient.setQueryData(['payroll', 'templates'], data),
  });

  return (
    <Panel title={t('payroll.templates.choose')} onClose={onClose}>
      <p className="hint">{t('payroll.templates.chooseHint')}</p>
      {(save.error || error) && <p className="form-error" role="alert">{save.error ? errorText(save.error) : error}</p>}
      <ul className="tpl-gallery">
        {templates.map((tpl) => {
          const text = templateText(tpl);
          const isDefault = (save.data?.defaultTemplate ?? current) === tpl.code;
          return (
            <li key={tpl.code} className={isDefault ? 'tpl-option is-default' : 'tpl-option'}>
              <Thumb template={tpl} />
              <div className="tpl-option__text">
                <strong>{text.name}</strong>
                <span className="hint">{text.description}</span>
              </div>
              <div className="tpl-option__actions">
                <button type="button" className="btn small" onClick={() => void openBlob(() => api.payroll.templateSample(tpl.code), setError)}>
                  {t('payroll.templates.sample')}
                </button>
                {isDefault
                  ? <Status tone="green">{t('payroll.templates.isDefault')}</Status>
                  : <button type="button" className="btn small primary" disabled={save.isPending} onClick={() => save.mutate(tpl.code)}>{t('payroll.templates.useThis')}</button>}
              </div>
            </li>
          );
        })}
      </ul>
    </Panel>
  );
}

/** Pick the layout for one generation (the company's default is chosen to start with). */
export function TemplateSelect({ value, onChange }: { value: string | null; onChange: (code: string) => void }) {
  const { t } = useTranslation();
  const templates = usePayslipTemplates();
  const chosen = value ?? templates.data?.defaultTemplate ?? null;
  if (!templates.data) return null;
  return (
    <fieldset className="tpl-select">
      <legend>{t('payroll.templates.forThisRun')}</legend>
      <div className="tpl-select__grid" role="radiogroup" aria-label={t('payroll.templates.forThisRun')}>
        {templates.data.templates.map((tpl) => (
          <label key={tpl.code} className={chosen === tpl.code ? 'tpl-pick is-on' : 'tpl-pick'}>
            <input type="radio" name="payslip-template" value={tpl.code} checked={chosen === tpl.code} onChange={() => onChange(tpl.code)} />
            <Thumb template={tpl} size="sm" />
            <span>{templateText(tpl).name}{tpl.code === templates.data.defaultTemplate && <small> · {t('payroll.templates.isDefault')}</small>}</span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}
