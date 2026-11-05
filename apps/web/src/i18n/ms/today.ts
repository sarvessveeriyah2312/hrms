import type { Shape } from '../shape';
import type { today as en } from '../en/today';

export const today: Shape<typeof en> = {
  today: {
    title: 'Hari ini',
    holiday: 'Cuti umum: {{name}}',
    pickDay: 'Hari untuk dilihat', backToToday: 'Kembali ke hari ini',
    truncated: 'Menunjukkan {{n}} orang pertama. Minta HR skop kehadiran yang lebih kecil untuk melihat semua orang.',
    search: 'Nama, nombor, jabatan atau tapak',
    site: 'Tapak', allSites: 'Semua tapak', noSite: 'Tiada tapak',
    status: 'Status', anyStatus: 'Semua status',
    statusName: { IN: 'Hadir', LATE: 'Lewat', NOT_IN: 'Belum masuk', ON_LEAVE: 'Bercuti', OFF: 'Tidak bekerja' },
    stillIn_one: '{{count}} masih berdaftar masuk', stillIn_other: '{{count}} masih berdaftar masuk',
    nPeople_one: 'daripada {{count}} orang', nPeople_other: 'daripada {{count}} orang',
    lateNote: 'Selepas waktu mula ditambah tempoh kelonggaran',
    absent: 'Tidak hadir',
    nOverdue_one: '{{count}} melepasi waktu mula', nOverdue_other: '{{count}} melepasi waktu mula',
    noneOverdue: 'Tiada yang terlewat',
    offNote_one: '{{count}} tidak bekerja hari ini', offNote_other: '{{count}} tidak bekerja hari ini',
    everyone: 'Semua orang', filters: 'Penapis hari ini',
    nobody: 'Belum ada sesiapa dalam skop kehadiran anda.', noMatch: 'Tiada sesiapa sepadan dengan penapis ini.',
    person: 'Orang', expected: 'Dijangka', in: 'Masuk', out: 'Keluar', note: 'Catatan',
    stillInShort: 'Masih di dalam',
    hours: '{{start}}–{{end}}',
    lateBy: 'Lewat · {{n}} min',
    notYet: 'Dijangka {{time}}',
    overdueBy: 'Belum masuk · {{n}} min',
    publicHoliday: 'Cuti umum', restDay: 'Hari rehat',
    leaveAm: '{{type}} (pagi)', leavePm: '{{type}} (petang)',
    workedHoliday: 'Bekerja pada cuti umum', workedRestDay: 'Bekerja pada hari rehat',
    flagged: 'Daftar masuk ditanda',
  },
};
