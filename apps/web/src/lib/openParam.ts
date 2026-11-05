import { useSearchParams } from 'react-router';

/**
 * A record to open from the link, e.g. /app/admin/roles?open=<id> from search. Pages show it until the person
 * picks something else; call `clear` then, so the link no longer overrides their choice.
 */
export function useOpenParam(name = 'open') {
  const [params, setParams] = useSearchParams();
  const linked = params.get(name);
  const clear = () => {
    if (!params.has(name)) return;
    const next = new URLSearchParams(params);
    next.delete(name);
    setParams(next, { replace: true });
  };
  return [linked, clear] as const;
}
