/** Attendance page tabs, and timesheets. */
export const timesheet = {
  attendance: {
    tabs: { today: 'Today', team: 'Timesheets', mine: 'My timesheet' },
    subtitle: {
      today: 'Who’s in, late, not in yet, on leave or off.',
      team: 'Hours, lateness, early leaving and missed clock-outs, person by person.',
      mine: 'Your clock-ins by day, with hours worked and anything HR may ask about.',
    },
  },
  timesheet: {
    hm: '{{h}}h {{m}}m', min: '{{n}} min',
    month: 'Month', prevMonth: 'Previous month', nextMonth: 'Next month',
    search: 'Name, number, department or site',
    issue: 'Show', anyone: 'Everyone',
    issues: { late: 'Late at least once', early: 'Left early at least once', missed: 'Missed a clock-out', absent: 'Absent at least once' },
    truncated: 'Showing the first {{n}} people. Ask HR for a narrower attendance scope to see everyone.',
    hoursWorked: 'Hours worked',
    nPeople_one: '{{count}} person', nPeople_other: '{{count}} people',
    late: 'Late', leftEarly: 'Left early', missedOut: 'Missed clock-outs', absent: 'Absent', leave: 'Leave days',
    timesTotal: '{{time}} in total',
    missedNote: 'Clocked in, never clocked out',
    leaveDays: '{{n}} days of leave',
    everyone: 'Everyone', filters: 'Timesheet filters',
    daysWorked: 'Days worked', hours: 'Hours',
    ofDays: '{{n}} / {{of}}', ofDaysLong: 'On {{n}} of {{of}} working days',
    countTime: '{{n}} · {{time}}',
    absentLeave: '{{absent}} absent · {{leave}} leave',
    days: 'Days', noDays: 'No days in this month yet.',
    date: 'Date', clockIns: 'Clock-ins', worked: 'Worked',
    present: 'Present', noClockOut: 'no clock-out',
  },
};
