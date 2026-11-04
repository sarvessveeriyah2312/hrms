import type { LeaveStatus } from '@hrms/api-client';
import i18n from '../i18n';

type Tone = 'amber' | 'green' | 'red' | 'grey' | 'blue';
const entry = (status: LeaveStatus, tone: Tone) => ({ tone, get label() { return i18n.t(`status.${status}`); } });

/** One label and colour per leave status, used everywhere leave appears. The label follows the current language. */
export const LEAVE_STATUS: Record<LeaveStatus, { tone: Tone; label: string }> = {
  DRAFT: entry('DRAFT', 'grey'),
  PENDING: entry('PENDING', 'amber'),
  APPROVED: entry('APPROVED', 'green'),
  REJECTED: entry('REJECTED', 'red'),
  RETURNED: entry('RETURNED', 'blue'),
  CANCELLED: entry('CANCELLED', 'grey'),
};
