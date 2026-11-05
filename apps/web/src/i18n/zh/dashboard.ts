import type { Shape } from '../shape';
import type { dashboard as en } from '../en/dashboard';

export const dashboard: Shape<typeof en> = {
  dashboard: {
    morning: '早上好', afternoon: '下午好', evening: '晚上好',
    greetName: '{{greeting}}，{{name}}',
    needOk_one: '有 {{count}} 条申请等待您批准。', needOk_other: '有 {{count}} 条申请等待您批准。',
    yoursWaiting_one: '您有 {{count}} 条申请正在等待审批。', yoursWaiting_other: '您有 {{count}} 条申请正在等待审批。',
    signedInTo: '您已登录 {{company}}。',
    waitingForYou: '等待您处理', oldest: '最早的一条：{{when}}', nothingToApprove: '没有需要审批的',
    offToday: '今日休假', approvedLeaveIn: '{{scope}} · 已批准的休假',
    typeLeft: '{{type}}剩余', ofTotal: '共 {{total}}', pendingDays: '{{days}}待审批', takenDays: '今年已休 {{days}}',
    peopleYouSee: '您可查看的员工', access: '{{scope}}权限',
    needsApproval: '需要您审批', seeAll: '查看全部', overdue: '已逾期',
    caughtUp: '没有待处理事项', caughtUpMeta: '团队的新申请会显示在这里。',
    yourLeave: '您的休假', with: '由 {{name}} 审批', noLeave: '暂无即将到来的休假', noLeaveMeta: '可在休假页面申请。',
    yourTeam: '您的团队', reportsTo: '汇报对象：{{name}}', noOne: '暂无其他成员', noOneMeta: '向您汇报的员工会显示在这里。',
    calendar: '日历', everyoneIn: '今天全员在岗',
  },
};
