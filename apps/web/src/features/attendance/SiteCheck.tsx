import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { SiteDetail } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { Glyph } from '../../components/Glyph';
import { Card } from '../../components/app/kit';

type Where = 'pin' | 'outside' | 'far' | 'none' | 'custom';
const FLAGS = ['FACE_NOT_VERIFIED', 'ROOTED_DEVICE', 'DEVELOPER_MODE', 'CLOCK_DRIFT', 'LOW_GPS_ACCURACY'] as const;
type Flag = (typeof FLAGS)[number];

/** Metres north of a point, as latitude (about 111.32 km per degree). */
const north = (lat: number, m: number) => Number((lat + m / 111_320).toFixed(6));

/**
 * Test a clock-in at this site: pick a person and where they are, and see what the server would decide with their
 * real sites. Nothing is saved.
 */
export function SiteCheck({ site }: { site: SiteDetail }) {
  const { t } = useTranslation();
  const [employeeId, setEmployeeId] = useState(site.members[0]?.employeeId ?? '');
  const [where, setWhere] = useState<Where>('pin');
  const [lat, setLat] = useState(String(site.lat));
  const [lng, setLng] = useState(String(site.lng));
  const [wifi, setWifi] = useState('');
  const [selfie, setSelfie] = useState(false);
  const [remarks, setRemarks] = useState('');
  const [mock, setMock] = useState(false);

  const point = (): { lat: number | null; lng: number | null } => {
    const la = Number(site.lat);
    const ln = Number(site.lng);
    switch (where) {
      case 'pin': return { lat: la, lng: ln };
      case 'outside': return { lat: north(la, site.radiusM + 30), lng: ln };
      case 'far': return { lat: north(la, 1000), lng: ln };
      case 'none': return { lat: null, lng: null };
      default: return { lat: Number(lat), lng: Number(lng) };
    }
  };
  const check = useMutation({
    mutationFn: () => api.sites.check({ employeeId, ...point(), accuracyM: 15, wifiBssid: wifi || null, selfie, remarks: remarks || null, mockLocation: mock }),
  });
  const r = check.data;
  const tone = r?.result === 'ACCEPTED' ? 'green' : r?.result === 'FLAGGED' ? 'orange' : 'pink';

  return (
    <Card title={t('sites.check.title')} padded>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); if (employeeId) check.mutate(); }}>
        <p className="hint">{t('sites.check.body')}</p>
        {site.members.length === 0 ? (
          <p className="hint">{t('sites.check.noPeople')}</p>
        ) : (
          <>
            <div className="field-row">
              <label className="field">
                {t('req.employee')}
                <select value={employeeId} onChange={(e) => setEmployeeId(e.target.value)}>
                  {site.members.map((m) => <option key={m.employeeId} value={m.employeeId}>{m.fullName}</option>)}
                </select>
              </label>
              <label className="field">
                {t('sites.check.where')}
                <select value={where} onChange={(e) => setWhere(e.target.value as Where)}>
                  {(['pin', 'outside', 'far', 'none', 'custom'] as Where[]).map((w) => (
                    <option key={w} value={w}>{t(`sites.check.at.${w}`, { m: site.radiusM + 30 })}</option>
                  ))}
                </select>
              </label>
            </div>
            {where === 'custom' && (
              <div className="field-row">
                <label className="field">{t('sites.check.lat')}<input inputMode="decimal" value={lat} onChange={(e) => setLat(e.target.value)} /></label>
                <label className="field">{t('sites.check.lng')}<input inputMode="decimal" value={lng} onChange={(e) => setLng(e.target.value)} /></label>
              </div>
            )}
            <div className="field-row">
              <label className="field">
                {t('sites.check.wifi')}
                <select value={wifi} onChange={(e) => setWifi(e.target.value)}>
                  <option value="">{t('sites.check.noWifi')}</option>
                  {site.wifi.map((w) => <option key={w.bssid} value={w.bssid}>{[w.ssid, w.bssid].filter(Boolean).join(' · ')}</option>)}
                  <option value="00:11:22:33:44:55">{t('sites.check.otherWifi')}</option>
                </select>
              </label>
              <label className="field">
                {t('sites.check.remark')}
                <input value={remarks} onChange={(e) => setRemarks(e.target.value)} maxLength={500} />
              </label>
            </div>
            <div className="site-check__toggles">
              <label className="check-line"><input type="checkbox" checked={selfie} onChange={(e) => setSelfie(e.target.checked)} />{t('sites.check.selfie')}</label>
              <label className="check-line"><input type="checkbox" checked={mock} onChange={(e) => setMock(e.target.checked)} />{t('sites.check.mock')}</label>
            </div>
            <div className="panel-actions">
              <button type="submit" className="btn" disabled={!employeeId || check.isPending}>{check.isPending ? t('sites.check.checking') : t('sites.check.run')}</button>
            </div>
          </>
        )}
        {check.error && <p className="form-error" role="alert">{errorText(check.error)}</p>}
        {r && (
          <div className={`site-check__result site-check__result--${tone}`} role="status">
            <Glyph icon={r.result === 'BLOCKED' ? 'close' : 'check'} tone={tone} size="lg" />
            <div>
              <strong>{t(`sites.check.result.${r.result}`)}</strong>
              <span>
                {r.result === 'BLOCKED'
                  ? r.message
                  : [r.site && t('sites.check.atSite', { site: r.site }), r.distanceM !== null && t('sites.check.distance', { d: r.distanceM, r: r.radiusM })]
                    .filter(Boolean).join(' · ')}
              </span>
              {r.flags.length > 0 && <span className="hint">{t('sites.check.flags', { flags: r.flags.map((f) => ((FLAGS as readonly string[]).includes(f) ? t(`sites.flag.${f as Flag}`) : f)).join(', ') })}</span>}
            </div>
          </div>
        )}
      </form>
    </Card>
  );
}
