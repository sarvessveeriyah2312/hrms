/** Approvals inbox and the request facts shown in panels. */
export const approvals = {
  approvals: {
    title: 'Approvals', subtitle: 'Requests where you are the current approver', approve: 'Approve', reject: 'Reject', empty: 'Nothing waiting for you.',
    emptyBody: 'New requests from your team will appear here.',
    gone: 'That request isn’t waiting for you any more: it was decided, withdrawn, or someone else acted on it.',
    waitingForYou: 'Waiting for you', waitingForOthers: 'Waiting for others',
    othersHint: 'You can approve company-wide, so you may step in when someone is stuck. Usually leave these to the approver.',
    waitingFor: 'Waiting for {{name}}', asBackup: 'You’re the backup: the approver is away or it’s overdue', asHr: 'HR approval',
    sendBack: 'Send back', sendBackFull: 'Send back for changes', rejectFull: 'Reject request',
    whatChange: 'What should they change?', whyReject: 'Why are you rejecting it?',
    changeExample: 'e.g. Attach the clinic’s MC, not a photo', theySee: 'They’ll see this',
    comment: 'Comment', commentHint: 'Optional when approving; needed to send back or reject',
  },
  req: {
    employee: 'Employee', dates: 'Dates', workingDays: 'Working days', leftIfApproved: 'Left if approved', daysOf: '{{left}} of {{total}}',
    reason: 'Reason', document: 'Document', submitted: 'Submitted', status: 'Status', recordedBy: 'Recorded by', chain: 'Approval chain',
  },
};
