import { useTranslation } from 'react-i18next';
import type { ProfileChangeDetail, ProfileSectionSpec, SectionData } from '@hrms/api-client';
import { useSectionSpecs } from './useSectionSpecs';
import { relative } from '../../lib/dates';
import { fieldLabel, formatValue, itemSummary, items, sectionLabel } from './sectionText';

/**
 * A profile change side by side: what the section said when it was sent and what's asked for, with changed fields
 * marked. List sections show each list in full on both sides. Warns when the section has changed since it was sent.
 */
export function ChangeCompare({ detail, showEmployee = false }: { detail: ProfileChangeDetail; showEmployee?: boolean }) {
  const { t } = useTranslation();
  const specs = useSectionSpecs();
  const spec = specs.data?.find((s) => s.key === detail.change.section);
  const c = detail.change;
  const stale = JSON.stringify(detail.current ?? {}) !== JSON.stringify(detail.live ?? {}) && c.status === 'PENDING';

  return (
    <section className="stack">
      <dl className="facts">
        {showEmployee && <><dt>{t('req.employee')}</dt><dd>{c.employeeName} ({c.employeeNo})</dd></>}
        <dt>{t('profile.sectionLabel')}</dt><dd>{sectionLabel(c.section)}</dd>
        <dt>{t('req.submitted')}</dt><dd>{relative(c.createdAt)}</dd>
        {c.note && <><dt>{t('profile.note')}</dt><dd>{c.note}</dd></>}
        {c.returnedComment && <><dt>{t('profile.sentBackBecause')}</dt><dd>{c.returnedComment}</dd></>}
      </dl>
      {stale && <p className="form-error" role="status">{t('profile.changedSince')}</p>}
      {spec ? <CompareTable spec={spec} before={detail.current} after={detail.proposed} changed={c.changedFields} /> : <p className="hint">{t('common.loading')}</p>}
    </section>
  );
}

function CompareTable({ spec, before, after, changed }: { spec: ProfileSectionSpec; before: SectionData; after: SectionData; changed: string[] }) {
  const { t } = useTranslation();
  if (spec.list) {
    const side = (data: SectionData) => {
      const list = items(data);
      if (list.length === 0) return <span className="hint">{t('profile.nothing')}</span>;
      return (
        <ul className="compare-list">
          {list.map((i, n) => {
            const s = itemSummary(spec, i);
            return <li key={n}><strong>{s.title}</strong>{s.meta && <small>{s.meta}</small>}</li>;
          })}
        </ul>
      );
    };
    return (
      <div className="compare-grid">
        <div><h3 className="panel-subhead">{t('profile.current')}</h3>{side(before)}</div>
        <div className="is-new"><h3 className="panel-subhead">{t('profile.new')}</h3>{side(after)}</div>
      </div>
    );
  }
  return (
    <div className="table-scroll">
      <table className="ui-table compare-table">
        <caption className="sr-only">{sectionLabel(spec.key)}</caption>
        <thead>
          <tr><th scope="col">{t('profile.fieldCol')}</th><th scope="col">{t('profile.current')}</th><th scope="col">{t('profile.new')}</th></tr>
        </thead>
        <tbody>
          {spec.fields.filter((f) => before?.[f.key] != null || after?.[f.key] != null).map((f) => {
            const isChanged = changed.includes(f.key);
            return (
              <tr key={f.key} className={isChanged ? 'is-changed' : undefined}>
                <th scope="row">{fieldLabel(f.key)}{isChanged && <span className="sr-only"> ({t('profile.changed')})</span>}</th>
                <td>{formatValue(f, before?.[f.key]) || <span className="hint">—</span>}</td>
                <td>{formatValue(f, after?.[f.key]) || <span className="hint">{t('profile.removed')}</span>}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
