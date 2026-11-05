import type { Shape } from '../shape';
import type { today as en } from '../en/today';

export const today: Shape<typeof en> = {
  today: {
    title: '今天',
    holiday: '公共假期：{{name}}',
    pickDay: '查看日期', backToToday: '回到今天',
    truncated: '仅显示前 {{n}} 人。如需查看所有人，请让 HR 缩小您的考勤查看范围。',
    search: '姓名、工号、部门或地点',
    site: '地点', allSites: '所有地点', noSite: '无地点',
    status: '状态', anyStatus: '所有状态',
    statusName: { IN: '已到岗', LATE: '迟到', NOT_IN: '尚未到岗', ON_LEAVE: '休假', OFF: '休息' },
    stillIn_one: '{{count}} 人仍在岗', stillIn_other: '{{count}} 人仍在岗',
    nPeople_one: '共 {{count}} 人', nPeople_other: '共 {{count}} 人',
    lateNote: '晚于上班时间加宽限',
    absent: '缺勤',
    nOverdue_one: '{{count}} 人已过上班时间', nOverdue_other: '{{count}} 人已过上班时间',
    noneOverdue: '无人超时',
    offNote_one: '今天 {{count}} 人休息', offNote_other: '今天 {{count}} 人休息',
    everyone: '所有人', filters: '今天的筛选',
    nobody: '您的考勤查看范围内暂无人员。', noMatch: '没有符合筛选条件的人。',
    person: '人员', expected: '预期', in: '上班', out: '下班', note: '备注',
    stillInShort: '仍在岗',
    hours: '{{start}}–{{end}}',
    lateBy: '迟到 · {{n}} 分钟',
    notYet: '应于 {{time}}',
    overdueBy: '未到岗 · {{n}} 分钟',
    publicHoliday: '公共假期', restDay: '休息日',
    leaveAm: '{{type}}（上午）', leavePm: '{{type}}（下午）',
    workedHoliday: '公共假期上班', workedRestDay: '休息日上班',
    flagged: '打卡被标记',
  },
};
