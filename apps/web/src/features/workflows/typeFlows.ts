import type { TFunction } from 'i18next';
import { useQuery } from '@tanstack/react-query';
import type { ApprovalFlow, FlowSubject } from '@hrms/api-client';
import { api } from '../../lib/api';

/** Switched-on workflows that can apply to a leave or claim type (any type, or this one), in checking order. */
export function flowsFor(flows: ApprovalFlow[] | undefined, typeId: string | null): ApprovalFlow[] {
  return (flows ?? [])
    .filter((f) => f.active && (f.typeIds.length === 0 || (typeId !== null && f.typeIds.includes(typeId))))
    .sort((a, b) => a.position - b.position);
}

/** A workflow with no department or amount/length condition: it takes every request of the type. */
export const coversAll = (f: ApprovalFlow) => f.departmentIds.length === 0 && f.minQuantity === null && f.maxQuantity === null;

/** The first workflow that takes every request of the type, if any (later ones and the type's own chain never run). */
export function coveringFlow(flows: ApprovalFlow[]): ApprovalFlow | undefined {
  return flows.find(coversAll);
}

/** "Approval: Monthly Claims (workflow)", "Approval: workflows, else Manager → HR", "Approval: Manager". */
export function approvalText(t: TFunction, flows: ApprovalFlow[] | undefined, typeId: string, chain: string) {
  if (!flows) return t('ls.approval', { chain });
  const applicable = flowsFor(flows, typeId);
  const covering = coveringFlow(applicable);
  if (covering) return t('tw.byFlow', { name: covering.name });
  if (applicable.length) return t('tw.flowsThenChain', { chain });
  return t('ls.approval', { chain });
}

/** The subject's workflows, when you may read them (workflow:manage, or the subject's settings permission). */
export function useSubjectFlows(subject: FlowSubject) {
  return useQuery({ queryKey: ['workflow', 'flows', subject], queryFn: () => api.workflow.flows(subject), retry: false });
}
