import type { Shape } from '../shape';
import type { timesheet as en } from '../en/timesheet';

export const timesheet: Shape<typeof en> = {
  attendance: {
    tabs: { today: '今天', team: '考勤表', mine: '我的考勤表' },
    subtitle: {
      today: '谁已到岗、迟到、尚未到岗、休假或休息。',
      team: '每个人的工时、迟到、早退和漏打下班卡。',
      mine: '您每天的打卡记录、工时以及 HR 可能会询问的情况。',
    },
  },
  timesheet: {
    hm: '{{h}}小时{{m}}分', min: '{{n}} 分钟',
    month: '月份', prevMonth: '上个月', nextMonth: '下个月',
    search: '姓名、工号、部门或地点',
    issue: '显示', anyone: '所有人',
    issues: { late: '至少迟到一次', early: '至少早退一次', missed: '漏打下班卡', absent: '至少缺勤一次' },
    truncated: '仅显示前 {{n}} 人。如需查看所有人，请让 HR 缩小您的考勤查看范围。',
    hoursWorked: '工时',
    nPeople_one: '{{count}} 人', nPeople_other: '{{count}} 人',
    late: '迟到', leftEarly: '早退', missedOut: '漏打下班卡', absent: '缺勤', leave: '休假天数',
    timesTotal: '共 {{time}}',
    missedNote: '已打上班卡，未打下班卡',
    leaveDays: '{{n}} 天休假',
    everyone: '所有人', filters: '考勤表筛选',
    daysWorked: '出勤天数', hours: '工时',
    ofDays: '{{n}} / {{of}}', ofDaysLong: '{{of}} 个工作日中出勤 {{n}} 天',
    countTime: '{{n}} · {{time}}',
    absentLeave: '缺勤 {{absent}} · 休假 {{leave}}',
    days: '每日记录', noDays: '本月暂无记录。',
    date: '日期', clockIns: '打卡', worked: '工时',
    present: '出勤', noClockOut: '未打下班卡',
  },
};
