import type { ClaimType } from '@hrms/api-client';
import i18n from '../../i18n';

/** "Manager → HR": a claim type's own approval chain (used when no approval workflow matches). */
export function chainText(ct: Pick<ClaimType, 'approvalLevels' | 'hrApproval'>) {
  const t = i18n.t;
  const parts = ct.approvalLevels === 0 ? [] : ct.approvalLevels === 1 ? [t('ls.chainManager')] : [t('ls.chainManager'), t('ls.chainTheirManager')];
  if (ct.hrApproval || parts.length === 0) parts.push(t('ls.chainHr'));
  return parts.join(' → ');
}
