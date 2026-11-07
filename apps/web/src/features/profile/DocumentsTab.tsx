import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { DocumentCategory, FileMeta } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { relative } from '../../lib/dates';
import { Card, List, Row } from '../../components/app/kit';
import { Glyph } from '../../components/Glyph';
import { AttachmentButton, FileField } from '../../components/app/files';

const CATEGORIES: DocumentCategory[] = ['CONTRACT', 'IDENTITY', 'CERTIFICATE', 'LETTER', 'MEDICAL', 'OTHER'];

/** Documents on the record: the person and HR add them (PDF, JPG or PNG up to 5 MB); only they can see them. */
export function DocumentsTab({ id, canAdd }: { id: string; canAdd: boolean }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const docs = useQuery({ queryKey: ['profile', id, 'documents'], queryFn: () => api.profiles.documents(id) });
  const [file, setFile] = useState<FileMeta | null>(null);
  const [category, setCategory] = useState<DocumentCategory>('CERTIFICATE');
  const [description, setDescription] = useState('');
  const [formKey, setFormKey] = useState(0);
  const [confirming, setConfirming] = useState<string | null>(null);

  const add = useMutation({
    mutationFn: () => api.profiles.addDocument(id, { fileId: file!.id, category, description: description.trim() || undefined }),
    onSuccess: async () => {
      setFile(null);
      setDescription('');
      setFormKey((k) => k + 1);
      await queryClient.invalidateQueries({ queryKey: ['profile', id, 'documents'] });
    },
  });
  const remove = useMutation({
    mutationFn: (documentId: string) => api.profiles.deleteDocument(id, documentId),
    onSuccess: async () => {
      setConfirming(null);
      await queryClient.invalidateQueries({ queryKey: ['profile', id, 'documents'] });
    },
  });

  return (
    <div className="grid grid--main">
      <Card title={t('profile.docs.title')} count={docs.data?.length}>
        {docs.isError && <p className="form-error" role="alert">{errorText(docs.error)}</p>}
        {remove.error && <p className="form-error" role="alert">{errorText(remove.error)}</p>}
        <List>
          {docs.data?.map((d) => (
            <Row key={d.id} lead={<Glyph icon="file" tone="blue" />}
              title={d.description || d.file?.name || t('profile.docs.document')}
              meta={[t(`profile.docs.category.${d.category}`), d.uploadedBy && t('profile.docs.addedBy', { name: d.uploadedBy }), relative(d.createdAt)].filter(Boolean).join(' · ')}
              trail={(
                <span className="profile-doc-actions">
                  {d.file && <AttachmentButton requestId={d.id} file={d.file} load={(docId) => api.profiles.documentFile(id, docId)} />}
                  {d.canDelete && (confirming === d.id ? (
                    <>
                      <button type="button" className="btn small reject" onClick={() => remove.mutate(d.id)} disabled={remove.isPending}>{t('profile.docs.confirmRemove')}</button>
                      <button type="button" className="btn small" onClick={() => setConfirming(null)}>{t('common.cancel')}</button>
                    </>
                  ) : (
                    <button type="button" className="btn small" onClick={() => setConfirming(d.id)}>{t('common.remove')}</button>
                  ))}
                </span>
              )} />
          ))}
          {docs.data && docs.data.length === 0 && <Row title={t('profile.docs.none')} meta={canAdd ? t('profile.docs.noneHint') : undefined} />}
          {docs.isPending && <Row title={t('common.loading')} />}
        </List>
      </Card>

      {canAdd && (
        <Card title={t('profile.docs.add')} padded>
          <form className="stack" key={formKey} onSubmit={(e) => { e.preventDefault(); if (file) add.mutate(); }}>
            {add.error && <p className="form-error" role="alert">{errorText(add.error)}</p>}
            <FileField label={t('profile.docs.file')} required current={null} onChange={setFile} />
            <label className="field">
              {t('profile.docs.kind')}
              <select value={category} onChange={(e) => setCategory(e.target.value as DocumentCategory)}>
                {CATEGORIES.map((c) => <option key={c} value={c}>{t(`profile.docs.category.${c}`)}</option>)}
              </select>
            </label>
            <label className="field">
              <span className="field-label">{t('profile.docs.description')}<span className="field-optional">{t('common.optional')}</span></span>
              <input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={200} placeholder={t('profile.docs.descriptionHint')} />
            </label>
            <div className="panel-actions">
              <button type="submit" className="btn primary" disabled={!file || add.isPending}>{t('profile.docs.addButton')}</button>
            </div>
          </form>
        </Card>
      )}
    </div>
  );
}
