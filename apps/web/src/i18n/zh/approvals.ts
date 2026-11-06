import type { Shape } from '../shape';
import type { approvals as en } from '../en/approvals';

export const approvals: Shape<typeof en> = {
  approvals: {
    title: '审批', subtitle: '当前由您审批的申请', approve: '批准', reject: '拒绝', empty: '没有等待您处理的申请。',
    emptyBody: '团队的新申请会显示在这里。',
    gone: '该申请已不再等待您处理：它已有结果、已被撤回，或已由他人处理。',
    waitingForYou: '等待您处理', waitingForOthers: '等待他人处理',
    othersHint: '您拥有全公司的审批权限，因此在流程卡住时可以介入。通常请交由原审批人处理。',
    waitingFor: '等待 {{name}} 处理', asBackup: '您是代理审批人：原审批人不在或申请已逾期', asHr: '人事审批',
    sendBack: '退回', sendBackFull: '退回修改', rejectFull: '拒绝申请',
    whatChange: '需要对方修改什么？', whyReject: '拒绝的原因是什么？',
    changeExample: '例如：请附上诊所开具的病假单，而不是照片', theySee: '对方会看到这条内容',
    comment: '备注', commentHint: '批准时可不填；退回或拒绝时必填',
    many: {
      selectAll: '全选 {{count}} 项加班', clear: '取消选择',
      chosen_one: '已选择 {{count}} 项加班申请', chosen_other: '已选择 {{count}} 项加班申请',
      approve: '批准 {{count}} 项', commentHint: '可不填，会随每项批准一并发送',
      someFailed_one: '有 {{count}} 项申请未能批准：', someFailed_other: '有 {{count}} 项申请未能批准：',
      pick: '选择 {{who}} 的 {{title}}',
    },
  },
  req: {
    employee: '员工', dates: '日期', workingDays: '工作日', leftIfApproved: '批准后剩余', daysOf: '{{left}} / {{total}}',
    reason: '事由', document: '文件', submitted: '提交时间', status: '状态', recordedBy: '登记人', chain: '审批链',
  },
};
