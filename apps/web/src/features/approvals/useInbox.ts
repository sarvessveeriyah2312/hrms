import { useQuery } from '@tanstack/react-query';
import { api } from '../../lib/api';

/** The approver's inbox, refreshed every minute. Shared by the page, Home and the sidebar badge. */
export function useInbox(enabled = true) {
  return useQuery({ queryKey: ['approvals', 'inbox'], queryFn: api.approvals.inbox, refetchInterval: 60_000, enabled });
}
