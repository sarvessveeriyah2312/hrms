import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ApiError, type ClockMethod, type SiteDetail, type SiteInput, type SiteSummary, type SiteWifi, type WifiMode } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { useOpenParam } from '../../lib/openParam';
import { Glyph } from '../../components/Glyph';
import { Icon, type IconName } from '../../components/Icon';
import { Card, EmptyState, PageHeader, Status, Toggle } from '../../components/app/kit';
import { GeofenceMap } from '../../components/app/GeofenceMap';
import { SitePeople } from './SitePeople';
import { SiteCheck } from './SiteCheck';
import './sites.css';

const METHODS: { key: ClockMethod; icon: IconName }[] = [
  { key: 'GEOFENCE', icon: 'pin' },
  { key: 'WIFI', icon: 'wifi' },
  { key: 'KIOSK', icon: 'tablet' },
  { key: 'SELFIE', icon: 'face' },
  { key: 'FACE', icon: 'shield' },
];
const MIN_RADIUS = 25;
const MAX_RADIUS = 1000;

/**
 * Attendance → Sites: where people may clock in (geofence and/or office Wi-Fi), which methods each site accepts,
 * and who belongs to it. Needs "Manage attendance rules". ?open=<site id> opens a site.
 */
export function SitesPage() {
  const { t } = useTranslation();
  const sites = useQuery({ queryKey: ['sites'], queryFn: api.sites.list });
  const [openId, clearOpen] = useOpenParam();
  const [picked, setPicked] = useState<string | 'new' | null>(null);
  const selected = openId ?? picked ?? sites.data?.[0]?.id ?? null;
  const select = (id: string | 'new' | null) => {
    setPicked(id);
    clearOpen();
  };
  const list = sites.data ?? [];

  return (
    <>
      <PageHeader
        title={t('nav.sites')}
        subtitle={t('sites.subtitle')}
        actions={<button type="button" className="btn primary" onClick={() => select('new')}><Icon name="plus" size={16} />{t('sites.new')}</button>}
      />
      {sites.isError && <p className="form-error" role="alert">{errorText(sites.error)}</p>}

      {sites.data && list.length === 0 && selected !== 'new' ? (
        <Card>
          <EmptyState icon="pin" tone="teal" title={t('sites.noneTitle')}
            action={<button type="button" className="btn primary" onClick={() => select('new')}>{t('sites.addFirst')}</button>}>
            {t('sites.noneBody')}
          </EmptyState>
        </Card>
      ) : (
        <div className="sites-layout">
          <SiteList sites={list} selected={selected} onSelect={select} />
          {selected === 'new' ? (
            <SiteEditor key="new" site={null} onSaved={(id) => select(id)} onDeleted={() => select(null)} />
          ) : selected ? (
            <SiteLoader key={selected} id={selected} onDeleted={() => select(null)} />
          ) : null}
        </div>
      )}
    </>
  );
}

function SiteList({ sites, selected, onSelect }: { sites: SiteSummary[]; selected: string | null; onSelect: (id: string) => void }) {
  const { t } = useTranslation();
  return (
    <Card title={t('nav.sites')} count={sites.length} className="sites-list">
      <ul className="ui-list">
        {sites.map((s) => (
          <li key={s.id}>
            <button type="button" className={s.id === selected ? 'ui-row ui-row--link is-selected' : 'ui-row ui-row--link'}
              aria-current={s.id === selected} onClick={() => onSelect(s.id)}>
              <span className="ui-row__lead"><Glyph icon="pin" tone={s.active ? 'teal' : 'blue'} /></span>
              <span className="ui-row__text">
                <span className="ui-row__title">{s.name}</span>
                <span className="ui-row__meta">
                  {[t('common.person', { count: s.people }), `${s.radiusM} m`, s.methods.map((m) => t(`sites.method.${m}.short`)).join(', ')].join(' · ')}
                </span>
              </span>
              {!s.active && <Status tone="grey">{t('common.off')}</Status>}
            </button>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function SiteLoader({ id, onDeleted }: { id: string; onDeleted: () => void }) {
  const site = useQuery({ queryKey: ['sites', id], queryFn: () => api.sites.get(id) });
  if (site.isError) return <p className="form-error" role="alert">{errorText(site.error)}</p>;
  if (!site.data) return <Card padded><p className="muted">…</p></Card>;
  return (
    <div className="col">
      <SiteEditor key={site.data.updatedAt} site={site.data} onSaved={() => undefined} onDeleted={onDeleted} />
      <SitePeople site={site.data} />
      <SiteCheck key={`${site.data.id}-check`} site={site.data} />
    </div>
  );
}

type Form = Omit<SiteInput, 'lat' | 'lng'> & { lat: number | null; lng: number | null };

function toForm(s: SiteDetail | null): Form {
  return {
    name: s?.name ?? '', address: s?.address ?? '', entityId: s?.entityId ?? null, active: s?.active ?? true,
    lat: s ? Number(s.lat) : null, lng: s ? Number(s.lng) : null, radiusM: s?.radiusM ?? 100,
    methods: s?.methods ?? ['GEOFENCE'], wifiMode: s?.wifiMode ?? 'GPS_OR_WIFI', remarksRequired: s?.remarksRequired ?? false,
    wifi: s?.wifi.map((w) => ({ ...w })) ?? [],
  };
}

type Place = { display_name: string; lat: string; lon: string };

function SiteEditor({ site, onSaved, onDeleted }: { site: SiteDetail | null; onSaved: (id: string) => void; onDeleted: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const entities = useQuery({ queryKey: ['sites', 'entities'], queryFn: api.sites.entities });
  const [form, setForm] = useState<Form>(() => toForm(site));
  const [places, setPlaces] = useState<Place[] | null>(null);
  const [finding, setFinding] = useState(false);
  const [findError, setFindError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const set = <K extends keyof Form>(k: K, v: Form[K]) => setForm((f) => ({ ...f, [k]: v }));
  const has = (m: ClockMethod) => form.methods.includes(m);
  const toggleMethod = (m: ClockMethod) => set('methods', has(m) ? form.methods.filter((x) => x !== m) : [...form.methods, m]);

  const save = useMutation({
    mutationFn: () => {
      const input: SiteInput = { ...form, lat: form.lat!, lng: form.lng!, address: form.address?.trim() || null,
        wifi: form.wifi.filter((w) => w.bssid.trim() || w.ssid?.trim()) };
      return site ? api.sites.update(site.id, input) : api.sites.create(input);
    },
    onSuccess: async (saved) => {
      queryClient.setQueryData(['sites', saved.id], saved);
      await queryClient.invalidateQueries({ queryKey: ['sites'], exact: true });
      onSaved(saved.id);
    },
  });
  const remove = useMutation({
    mutationFn: () => api.sites.remove(site!.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['sites'] });
      onDeleted();
    },
  });

  const fields = save.error instanceof ApiError ? ((save.error.problem as { fields?: Record<string, string> }).fields ?? {}) : {};
  const fieldError = (k: string) => fields[k] && <span className="error" role="alert">{fields[k]}</span>;
  const otherError = save.error && Object.keys(fields).length === 0 ? errorText(save.error) : null;
  const ready = !!form.name.trim() && form.lat !== null && form.lng !== null;

  // Address → map pin, with OpenStreetMap's geocoder (only when asked, never while typing).
  const find = async () => {
    const q = form.address?.trim();
    if (!q) return;
    setFinding(true);
    setFindError(null);
    setPlaces(null);
    try {
      const res = await fetch(`https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q=${encodeURIComponent(q)}`, {
        headers: { Accept: 'application/json' },
      });
      const list = (await res.json()) as Place[];
      if (list.length === 0) setFindError(t('sites.notFound'));
      else if (list.length === 1) placeAt(list[0]);
      else setPlaces(list);
    } catch {
      setFindError(t('sites.findFailed'));
    } finally {
      setFinding(false);
    }
  };
  const placeAt = (p: Place) => {
    setForm((f) => ({ ...f, lat: Number(Number(p.lat).toFixed(6)), lng: Number(Number(p.lon).toFixed(6)) }));
    setPlaces(null);
  };
  const here = () => {
    setFindError(null);
    navigator.geolocation?.getCurrentPosition(
      (pos) => setForm((f) => ({ ...f, lat: Number(pos.coords.latitude.toFixed(6)), lng: Number(pos.coords.longitude.toFixed(6)) })),
      () => setFindError(t('sites.noLocation')),
      { enableHighAccuracy: true, timeout: 10_000 },
    );
  };

  const setWifi = (i: number, patch: Partial<SiteWifi>) => set('wifi', form.wifi.map((w, j) => (j === i ? { ...w, ...patch } : w)));

  return (
    <form className="col site-editor" onSubmit={(e) => { e.preventDefault(); if (ready) save.mutate(); }}>
      {/* ---- details ---- */}
      <Card title={site ? site.name : t('sites.new')} padded
        action={<Toggle checked={form.active} onChange={(on) => set('active', on)} label={form.active ? t('common.on') : t('common.off')} />}>
        <div className="stack">
          {otherError && <p className="form-error" role="alert">{otherError}</p>}
          <div className="field-row">
            <label className="field">
              {t('sites.name')}
              <input value={form.name} onChange={(e) => set('name', e.target.value)} maxLength={80} placeholder={t('sites.nameExample')} required />
              {fieldError('name')}
            </label>
            {entities.data && entities.data.length > 1 && (
              <label className="field">
                {t('sites.entity')}
                <select value={form.entityId ?? entities.data[0].id} onChange={(e) => set('entityId', e.target.value)}>
                  {entities.data.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
                </select>
              </label>
            )}
          </div>
          {!form.active && <p className="hint">{t('sites.offHint')}</p>}
        </div>
      </Card>

      {/* ---- location ---- */}
      <Card title={t('sites.locationTitle')} padded>
        <div className="stack">
          <div className="site-find">
            <label className="field">
              {t('sites.address')}
              <input value={form.address ?? ''} onChange={(e) => set('address', e.target.value)} maxLength={300}
                placeholder={t('sites.addressExample')}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void find(); } }} />
            </label>
            <button type="button" className="btn" onClick={() => void find()} disabled={finding || !form.address?.trim()}>
              <Icon name="search" size={15} />{finding ? t('sites.finding') : t('sites.find')}
            </button>
            <button type="button" className="btn" onClick={here} title={t('sites.useMine')}><Icon name="pin" size={15} />{t('sites.useMine')}</button>
          </div>
          {findError && <p className="hint site-find__error" role="alert">{findError}</p>}
          {places && (
            <ul className="site-places" aria-label={t('sites.pickPlace')}>
              {places.map((p) => (
                <li key={`${p.lat},${p.lon}`}><button type="button" onClick={() => placeAt(p)}><Icon name="pin" size={14} />{p.display_name}</button></li>
              ))}
            </ul>
          )}

          <GeofenceMap lat={form.lat} lng={form.lng} radiusM={form.radiusM} label={t('sites.mapLabel')}
            onMove={(lat, lng) => setForm((f) => ({ ...f, lat, lng }))} />
          <p className="hint">{form.lat === null ? t('sites.mapHintEmpty') : t('sites.mapHint')}</p>
          {fieldError('location')}

          <div className="site-radius">
            <label className="field">
              <span className="field-label"><span>{t('sites.radius')}</span><span className="field-optional">{t('sites.radiusValue', { m: form.radiusM })}</span></span>
              <input type="range" min={MIN_RADIUS} max={MAX_RADIUS} step={5} value={form.radiusM}
                onChange={(e) => set('radiusM', Number(e.target.value))} aria-valuetext={t('sites.radiusValue', { m: form.radiusM })} />
            </label>
            <label className="field site-radius__num">
              {t('sites.metres')}
              <input type="number" min={MIN_RADIUS} max={MAX_RADIUS} step={5} value={form.radiusM}
                onChange={(e) => set('radiusM', Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, Number(e.target.value) || MIN_RADIUS)))} />
            </label>
          </div>
          <p className="hint">{t('sites.radiusHint')}</p>
          {fieldError('radiusM')}
          {form.lat !== null && form.lng !== null && (
            <p className="hint site-coords">{t('sites.coords', { lat: form.lat.toFixed(6), lng: form.lng.toFixed(6) })}</p>
          )}
        </div>
      </Card>

      {/* ---- methods ---- */}
      <Card title={t('sites.methodsTitle')} padded>
        <div className="stack">
          <p className="hint">{t('sites.methodsHint')}</p>
          <div className="site-methods" role="group" aria-label={t('sites.methodsTitle')}>
            {METHODS.map((m) => (
              <label key={m.key} className={has(m.key) ? 'site-method is-on' : 'site-method'}>
                <input type="checkbox" checked={has(m.key)} onChange={() => toggleMethod(m.key)} />
                <Glyph icon={m.icon} tone={has(m.key) ? 'teal' : 'blue'} size="lg" />
                <span className="site-method__text">
                  <strong>{t(`sites.method.${m.key}.name`)}</strong>
                  <small>{t(`sites.method.${m.key}.body`)}</small>
                </span>
                <span className="site-method__check" aria-hidden="true"><Icon name="check" size={14} /></span>
              </label>
            ))}
          </div>
          {fieldError('methods')}
          {has('GEOFENCE') && has('WIFI') && (
            <fieldset>
              <legend>{t('sites.ruleTitle')}</legend>
              <div className="segmented" role="radiogroup" aria-label={t('sites.ruleTitle')}>
                {(['GPS_OR_WIFI', 'GPS_AND_WIFI'] as WifiMode[]).map((mode) => (
                  <label key={mode} className={form.wifiMode === mode ? 'is-on' : undefined}>
                    <input type="radio" name="wifi-mode" checked={form.wifiMode === mode} onChange={() => set('wifiMode', mode)} />
                    {t(`sites.rule.${mode}`)}
                  </label>
                ))}
              </div>
              <p className="hint">{t(`sites.ruleHint.${form.wifiMode}`)}</p>
            </fieldset>
          )}
          <label className="check-line">
            <input type="checkbox" checked={form.remarksRequired} onChange={(e) => set('remarksRequired', e.target.checked)} />
            {t('sites.remarksRequired')}
          </label>
        </div>
      </Card>

      {/* ---- Wi-Fi ---- */}
      {has('WIFI') && (
        <Card title={t('sites.wifiTitle')} count={form.wifi.length} padded>
          <div className="stack">
            <p className="hint">{t('sites.wifiHint')}</p>
            {form.wifi.length > 0 && (
              <div className="site-wifi" role="table" aria-label={t('sites.wifiTitle')}>
                <div className="site-wifi__head" role="row">
                  <span role="columnheader">{t('sites.ssid')}</span>
                  <span role="columnheader">{t('sites.bssid')}</span>
                  <span role="columnheader">{t('sites.wifiLabel')}</span>
                  <span role="columnheader" className="sr-only">{t('common.remove')}</span>
                </div>
                {form.wifi.map((w, i) => (
                  <div key={i} className="site-wifi__row" role="row">
                    <input className="input" value={w.ssid ?? ''} onChange={(e) => setWifi(i, { ssid: e.target.value })} placeholder="ACME-OFFICE"
                      aria-label={t('sites.ssid')} maxLength={32} role="cell" />
                    <input className="input site-wifi__bssid" value={w.bssid} onChange={(e) => setWifi(i, { bssid: e.target.value })} placeholder="3c:84:6a:12:9f:01"
                      aria-label={t('sites.bssid')} spellCheck={false} role="cell" />
                    <input className="input" value={w.label ?? ''} onChange={(e) => setWifi(i, { label: e.target.value })} placeholder={t('sites.wifiLabelExample')}
                      aria-label={t('sites.wifiLabel')} maxLength={80} role="cell" />
                    <button type="button" className="icon-pill" onClick={() => set('wifi', form.wifi.filter((_, j) => j !== i))}
                      aria-label={t('sites.removeWifi', { name: w.ssid || w.bssid || i + 1 })} role="cell"><Icon name="close" size={14} /></button>
                  </div>
                ))}
              </div>
            )}
            {fieldError('wifi')}
            <div>
              <button type="button" className="btn small" onClick={() => set('wifi', [...form.wifi, { bssid: '', ssid: form.wifi[0]?.ssid ?? '', label: '' }])}
                disabled={form.wifi.length >= 50}>
                <Icon name="plus" size={14} />{t('sites.addWifi')}
              </button>
            </div>
          </div>
        </Card>
      )}

      {/* ---- save ---- */}
      <div className="site-savebar">
        <button type="submit" className="btn primary" disabled={!ready || save.isPending}>
          {save.isPending ? t('common.saving') : site ? t('common.saveChanges') : t('sites.create')}
        </button>
        {save.isSuccess && !save.isPending && <span className="hint" role="status"><Icon name="check" size={14} /> {t('mail.saved')}</span>}
        {site && (
          confirmDelete ? (
            <span className="site-savebar__danger">
              <span className="hint">{site.hasPunches ? t('sites.cantDelete') : t('sites.deleteQ')}</span>
              {!site.hasPunches && <button type="button" className="btn reject" onClick={() => remove.mutate()} disabled={remove.isPending}>{t('common.delete')}</button>}
              <button type="button" className="btn" onClick={() => setConfirmDelete(false)}>{t('common.cancel')}</button>
            </span>
          ) : (
            <button type="button" className="btn reject site-savebar__delete" onClick={() => setConfirmDelete(true)}>{t('sites.delete')}</button>
          )
        )}
        {remove.error && <span className="error" role="alert">{errorText(remove.error)}</span>}
      </div>
    </form>
  );
}
