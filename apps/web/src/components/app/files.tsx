import { useId, useState } from 'react';
import type { FileMeta } from '@hrms/api-client';
import { useTranslation } from 'react-i18next';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { Icon } from '../Icon';

function size(bytes: number) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/**
 * Pick a document; it uploads straight away and gives back its id to attach.
 * `current` is a file already on the request (kept unless replaced or removed).
 */
export function FileField({ label, required, current, onChange }: {
  label: string;
  required?: boolean;
  current?: FileMeta | null;
  onChange: (file: FileMeta | null) => void;
}) {
  const { t } = useTranslation();
  const id = useId();
  const [file, setFile] = useState<FileMeta | null>(current ?? null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const pick = async (f: File | undefined) => {
    if (!f) return;
    setError(null);
    if (f.size > 5 * 1024 * 1024) {
      setError(t('files.tooBig'));
      return;
    }
    setBusy(true);
    try {
      const meta = await api.files.upload(f);
      setFile(meta);
      onChange(meta);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="field file-field">
      <span id={`${id}-label`}>{label}{required ? '' : <span className="hint"> {t('common.optional')}</span>}</span>
      {file ? (
        <div className="file-chip">
          <Icon name="file" size={16} />
          <span className="file-chip__name">{file.name}</span>
          <span className="hint">{size(file.sizeBytes)}</span>
          <button type="button" className="link-button" onClick={() => { setFile(null); onChange(null); }}>{t('common.remove')}</button>
        </div>
      ) : (
        <label className={busy ? 'file-drop is-busy' : 'file-drop'} htmlFor={id}>
          <Icon name="plus" size={16} />
          <span>{busy ? t('files.uploading') : t('files.choose')}</span>
          <input id={id} type="file" accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png" aria-labelledby={`${id}-label`}
            disabled={busy} onChange={(e) => void pick(e.target.files?.[0])} />
        </label>
      )}
      {error && <span className="error" role="alert">{error}</span>}
    </div>
  );
}

/** Opens a request's document in a new tab. It's fetched with the session, so a plain link wouldn't work. */
export function AttachmentButton({ requestId, file }: { requestId: string; file: FileMeta }) {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const open = async () => {
    setBusy(true);
    setError(null);
    // Open the tab first (inside the click) so pop-up blockers allow it, then point it at the file.
    const tab = window.open('', '_blank');
    try {
      const blob = await api.leave.attachment(requestId);
      const url = URL.createObjectURL(blob);
      if (tab) tab.location.href = url;
      else window.location.href = url;
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      tab?.close();
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <span className="file-chip">
      <Icon name="file" size={16} />
      <span className="file-chip__name">{file.name}</span>
      <span className="hint">{size(file.sizeBytes)}</span>
      <button type="button" className="link-button" onClick={() => void open()} disabled={busy}>{busy ? t('files.opening') : t('files.open')}</button>
      {error && <span className="error" role="alert">{error}</span>}
    </span>
  );
}
