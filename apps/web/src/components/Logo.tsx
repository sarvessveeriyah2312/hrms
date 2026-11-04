import { Link } from 'react-router';
import { PRODUCT_NAME } from '../brand';

/** Mark + wordmark. The mark is a clock face whose hands form a tick: clocked in, approved. */
export function Logo({ to = '/' }: { to?: string }) {
  return (
    <Link to={to} className="ui-logo" aria-label={`${PRODUCT_NAME} home`}>
      <svg className="ui-logo__mark" width="28" height="28" viewBox="0 0 28 28" aria-hidden="true">
        <rect width="28" height="28" rx="8" fill="currentColor" />
        <circle cx="14" cy="14" r="8.25" fill="none" stroke="var(--ui-logo-ink)" strokeWidth="2" />
        <path d="m10.25 14.25 2.75 2.75 5-5.5" fill="none" stroke="var(--ui-logo-ink)" strokeWidth="2.25" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      <span className="ui-logo__word">{PRODUCT_NAME}</span>
    </Link>
  );
}
