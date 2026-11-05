import type { Shape } from '../shape';
import type { dashboard as en } from '../en/dashboard';

export const dashboard: Shape<typeof en> = {
  dashboard: {
    morning: 'Selamat pagi', afternoon: 'Selamat petang', evening: 'Selamat malam',
    greetName: '{{greeting}}, {{name}}',
    needOk_one: '{{count}} permohonan memerlukan kelulusan anda.', needOk_other: '{{count}} permohonan memerlukan kelulusan anda.',
    yoursWaiting_one: '{{count}} permohonan anda sedang menunggu kelulusan.', yoursWaiting_other: '{{count}} permohonan anda sedang menunggu kelulusan.',
    signedInTo: 'Anda telah log masuk ke {{company}}.',
    waitingForYou: 'Menunggu anda', oldest: 'Yang paling lama tiba: {{when}}', nothingToApprove: 'Tiada untuk diluluskan',
    offToday: 'Tiada hari ini', approvedLeaveIn: '{{scope}} · cuti diluluskan',
    typeLeft: 'Baki {{type}}', ofTotal: 'daripada {{total}}', pendingDays: '{{days}} menunggu kelulusan', takenDays: '{{days}} diambil tahun ini',
    peopleYouSee: 'Kakitangan yang anda boleh lihat', access: 'Akses {{scope}}',
    needsApproval: 'Memerlukan kelulusan anda', seeAll: 'Lihat semua', overdue: 'Lewat',
    caughtUp: 'Tiada apa yang tertunggak', caughtUpMeta: 'Permohonan baharu daripada pasukan anda akan muncul di sini.',
    yourLeave: 'Cuti anda', with: 'Dengan {{name}}', noLeave: 'Tiada cuti akan datang', noLeaveMeta: 'Mohon dari halaman Cuti.',
    yourTeam: 'Pasukan anda', reportsTo: 'Melapor kepada {{name}}', noOne: 'Belum ada orang lain', noOneMeta: 'Kakitangan yang melapor kepada anda akan dipaparkan di sini.',
    calendar: 'Kalendar', everyoneIn: 'Semua hadir hari ini',
  },
};
