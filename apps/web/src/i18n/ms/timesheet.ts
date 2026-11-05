import type { Shape } from '../shape';
import type { timesheet as en } from '../en/timesheet';

export const timesheet: Shape<typeof en> = {
  attendance: {
    tabs: { today: 'Hari ini', team: 'Lembaran masa', mine: 'Lembaran masa saya' },
    subtitle: {
      today: 'Siapa yang hadir, lewat, belum masuk, bercuti atau tidak bekerja.',
      team: 'Jam bekerja, kelewatan, pulang awal dan daftar keluar yang tertinggal, mengikut orang.',
      mine: 'Daftar masuk anda mengikut hari, dengan jam bekerja dan apa-apa yang mungkin ditanya oleh HR.',
    },
  },
  timesheet: {
    hm: '{{h}}j {{m}}m', min: '{{n}} min',
    month: 'Bulan', prevMonth: 'Bulan sebelumnya', nextMonth: 'Bulan seterusnya',
    search: 'Nama, nombor, jabatan atau tapak',
    issue: 'Tunjuk', anyone: 'Semua orang',
    issues: { late: 'Lewat sekurang-kurangnya sekali', early: 'Pulang awal sekurang-kurangnya sekali', missed: 'Tertinggal daftar keluar', absent: 'Tidak hadir sekurang-kurangnya sekali' },
    truncated: 'Menunjukkan {{n}} orang pertama. Minta HR skop kehadiran yang lebih kecil untuk melihat semua orang.',
    hoursWorked: 'Jam bekerja',
    nPeople_one: '{{count}} orang', nPeople_other: '{{count}} orang',
    late: 'Lewat', leftEarly: 'Pulang awal', missedOut: 'Tertinggal daftar keluar', absent: 'Tidak hadir', leave: 'Hari cuti',
    timesTotal: 'Jumlah {{time}}',
    missedNote: 'Daftar masuk, tetapi tidak daftar keluar',
    leaveDays: '{{n}} hari cuti',
    everyone: 'Semua orang', filters: 'Penapis lembaran masa',
    daysWorked: 'Hari bekerja', hours: 'Jam',
    ofDays: '{{n}} / {{of}}', ofDaysLong: 'Pada {{n}} daripada {{of}} hari bekerja',
    countTime: '{{n}} · {{time}}',
    absentLeave: '{{absent}} tidak hadir · {{leave}} cuti',
    days: 'Hari', noDays: 'Belum ada hari dalam bulan ini.',
    date: 'Tarikh', clockIns: 'Daftar masuk', worked: 'Bekerja',
    present: 'Hadir', noClockOut: 'tiada daftar keluar',
  },
};
