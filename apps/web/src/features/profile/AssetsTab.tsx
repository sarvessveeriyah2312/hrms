import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { AssetInput, AssetKind, EmployeeAsset } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { formatDate, isoDate } from '../../lib/dates';
import { Card, Status } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Table } from '../../components/app/Table';

const KINDS: AssetKind[] = ['LAPTOP', 'PHONE', 'ACCESS_CARD', 'VEHICLE', 'UNIFORM', 'OTHER'];
const today = () => isoDate(new Date());
const day = (iso: string) => formatDate(iso, { day: 'numeric', month: 'short', year: 'numeric' });

/** Company assets issued to the person, with issue and return dates. HR records them; the person sees them. */
export function AssetsTab({ id, canManage }: { id: string; canManage: boolean }) {
  const { t } = useTranslation();
  const assets = useQuery({ queryKey: ['profile', id, 'assets'], queryFn: () => api.profiles.assets(id) });
  const [editing, setEditing] = useState<EmployeeAsset | 'new' | null>(null);
  const holding = assets.data?.filter((a) => !a.returnedOn).length ?? 0;

  return (
    <Card title={t('profile.assets.title')} count={assets.data?.length}
      action={canManage && <button type="button" className="btn small primary" onClick={() => setEditing('new')}>{t('profile.assets.issue')}</button>}>
      {assets.isError && <p className="form-error" role="alert">{errorText(assets.error)}</p>}
      {assets.data && assets.data.length > 0 && <p className="hint profile-assets-note">{t('profile.assets.holding', { count: holding })}</p>}
      <Table
        rows={assets.data ?? []}
        rowKey={(a) => a.id}
        label={t('profile.assets.title')}
        loading={assets.isPending}
        empty={t('profile.assets.none')}
        pageSizes={[]}
        columns={[
          { key: 'name', header: t('profile.assets.name'), rowHeader: true,
            cell: (a) => canManage
              ? <button type="button" className="link-button" onClick={() => setEditing(a)}>{a.name}</button>
              : a.name },
          { key: 'kind', header: t('profile.assets.kind'), cell: (a) => t(`profile.assets.kinds.${a.kind}`) },
          { key: 'tag', header: t('profile.assets.tag'), cell: (a) => a.tag ?? '—' },
          { key: 'issued', header: t('profile.assets.issuedOn'), cell: (a) => day(a.issuedOn) },
          { key: 'status', header: t('profile.assets.status'),
            cell: (a) => a.returnedOn
              ? <Status tone="grey">{t('profile.assets.returned', { date: day(a.returnedOn) })}</Status>
              : <Status tone="green">{t('profile.assets.withThem')}</Status> },
        ]}
      />
      {editing && <AssetPanel id={id} asset={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </Card>
  );
}

function AssetPanel({ id, asset, onClose }: { id: string; asset: EmployeeAsset | null; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [form, setForm] = useState<AssetInput>(() => (asset
    ? { kind: asset.kind, name: asset.name, tag: asset.tag, issuedOn: asset.issuedOn, returnedOn: asset.returnedOn, note: asset.note }
    : { kind: 'LAPTOP', name: '', tag: null, issuedOn: today(), returnedOn: null, note: null }));
  const [confirmDelete, setConfirmDelete] = useState(false);
  const set = <K extends keyof AssetInput>(k: K, v: AssetInput[K]) => setForm((f) => ({ ...f, [k]: v }));
  const done = async () => {
    await queryClient.invalidateQueries({ queryKey: ['profile', id, 'assets'] });
    onClose();
  };
  const save = useMutation({
    mutationFn: () => (asset ? api.profiles.updateAsset(id, asset.id, form) : api.profiles.addAsset(id, form)),
    onSuccess: done,
  });
  const remove = useMutation({ mutationFn: () => api.profiles.deleteAsset(id, asset!.id), onSuccess: done });
  const error = save.error ?? remove.error;

  return (
    <Panel title={asset ? t('profile.assets.editTitle', { name: asset.name }) : t('profile.assets.issueTitle')} onClose={onClose}>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
        {error && <p className="form-error" role="alert">{errorText(error)}</p>}
        <div className="field-row">
          <label className="field">
            {t('profile.assets.kind')}
            <select value={form.kind} onChange={(e) => set('kind', e.target.value as AssetKind)}>
              {KINDS.map((k) => <option key={k} value={k}>{t(`profile.assets.kinds.${k}`)}</option>)}
            </select>
          </label>
          <label className="field">
            {t('profile.assets.name')}
            <input value={form.name} onChange={(e) => set('name', e.target.value)} maxLength={120} required placeholder={t('profile.assets.nameHint')} />
          </label>
        </div>
        <label className="field">
          <span className="field-label">{t('profile.assets.tag')}<span className="field-optional">{t('common.optional')}</span></span>
          <input value={form.tag ?? ''} onChange={(e) => set('tag', e.target.value || null)} maxLength={60} />
        </label>
        <div className="field-row">
          <label className="field">
            {t('profile.assets.issuedOn')}
            <input type="date" value={form.issuedOn} onChange={(e) => set('issuedOn', e.target.value)} required />
          </label>
          <label className="field">
            <span className="field-label">{t('profile.assets.returnedOn')}<span className="field-optional">{t('common.optional')}</span></span>
            <input type="date" value={form.returnedOn ?? ''} min={form.issuedOn} onChange={(e) => set('returnedOn', e.target.value || null)} />
          </label>
        </div>
        <label className="field">
          <span className="field-label">{t('profile.assets.note')}<span className="field-optional">{t('common.optional')}</span></span>
          <textarea value={form.note ?? ''} onChange={(e) => set('note', e.target.value || null)} maxLength={300} />
        </label>
        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={save.isPending}>{t('common.save')}</button>
          {asset && !form.returnedOn && (
            <button type="button" className="btn" onClick={() => set('returnedOn', today())}>{t('profile.assets.markReturned')}</button>
          )}
          {asset && (confirmDelete ? (
            <button type="button" className="btn reject" onClick={() => remove.mutate()} disabled={remove.isPending}>{t('profile.assets.confirmDelete')}</button>
          ) : (
            <button type="button" className="btn reject" onClick={() => setConfirmDelete(true)}>{t('common.delete')}</button>
          ))}
        </div>
      </form>
    </Panel>
  );
}
