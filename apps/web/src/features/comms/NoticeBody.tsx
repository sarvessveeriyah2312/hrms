/** A notice's text as paragraphs (plain text: blank lines split paragraphs, single line breaks are kept). Never HTML. */
export function NoticeBody({ text }: { text: string }) {
  const paragraphs = text.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean);
  if (paragraphs.length === 0) return null;
  return (
    <div className="notice-body">
      {paragraphs.map((p, i) => <p key={i}>{p}</p>)}
    </div>
  );
}
