import type { TimelineStep } from '@hrms/api-client';
import { useTranslation } from 'react-i18next';
import { relative } from '../../lib/dates';
import { Status } from '../../components/app/kit';

const AS_KEY = { BACKUP: 'timeline.asBackup', HR: 'timeline.asHr', OVERRIDE: 'timeline.override', AUTO: 'timeline.auto' } as const;

/** The approval chain of one request: who it went to, who acted, and what they said. */
export function Timeline({ steps }: { steps: TimelineStep[] }) {
  const { t } = useTranslation();
  return (
    <ol className="timeline">
      {steps.map((s) => {
        const done = s.state === 'DONE';
        const tone = s.action === 'REJECT' ? 'red' : done ? 'green' : s.state === 'ACTIVE' ? 'amber' : 'grey';
        const status = t(s.action === 'REJECT' ? 'timeline.rejected' : done ? 'timeline.approved' : s.state === 'ACTIVE' ? 'timeline.waiting' : s.state === 'SKIPPED' ? 'timeline.skipped' : 'timeline.next');
        return (
          <li key={`${s.level}-${s.label}`} className={`timeline__step is-${s.state.toLowerCase()}`}>
            <span className="timeline__dot" aria-hidden="true" />
            <div className="timeline__body">
              <div className="timeline__head">
                <strong>{s.approver}</strong>
                <Status tone={tone}>{status}</Status>
              </div>
              <span className="timeline__meta">
                {s.label}
                {done && s.actedBy && s.actedBy !== s.approver && ` · ${s.actedBy}${s.actedAs && s.actedAs in AS_KEY ? ` ${t(AS_KEY[s.actedAs as keyof typeof AS_KEY])}` : ''}`}
                {s.actedAt && ` · ${relative(s.actedAt)}`}
              </span>
              {s.comment && <p className="timeline__comment">“{s.comment}”</p>}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
