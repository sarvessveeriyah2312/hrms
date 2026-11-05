import type { Shape } from '../shape';
import type { approvals as en } from '../en/approvals';

export const approvals: Shape<typeof en> = {
  approvals: {
    title: 'Kelulusan', subtitle: 'Permohonan di mana anda ialah pelulus semasa', approve: 'Lulus', reject: 'Tolak', empty: 'Tiada yang menunggu anda.',
    emptyBody: 'Permohonan baharu daripada pasukan anda akan muncul di sini.',
    gone: 'Permohonan itu tidak lagi menunggu anda: ia telah diputuskan, ditarik balik, atau orang lain telah bertindak ke atasnya.',
    waitingForYou: 'Menunggu anda', waitingForOthers: 'Menunggu orang lain',
    othersHint: 'Anda boleh meluluskan di seluruh syarikat, jadi anda boleh mengambil alih apabila sesuatu tersekat. Biasanya biarkan kepada pelulusnya.',
    waitingFor: 'Menunggu {{name}}', asBackup: 'Anda ialah pengganti: pelulus tiada atau ia telah lewat', asHr: 'Kelulusan HR',
    sendBack: 'Kembalikan', sendBackFull: 'Kembalikan untuk pindaan', rejectFull: 'Tolak permohonan',
    whatChange: 'Apa yang perlu mereka ubah?', whyReject: 'Mengapa anda menolaknya?',
    changeExample: 'cth. Lampirkan MC klinik, bukan gambar', theySee: 'Mereka akan melihat ini',
    comment: 'Ulasan', commentHint: 'Pilihan semasa meluluskan; wajib untuk mengembalikan atau menolak',
  },
  req: {
    employee: 'Pekerja', dates: 'Tarikh', workingDays: 'Hari bekerja', leftIfApproved: 'Baki jika diluluskan', daysOf: '{{left}} daripada {{total}}',
    reason: 'Sebab', document: 'Dokumen', submitted: 'Dihantar', status: 'Status', recordedBy: 'Direkod oleh', chain: 'Rantaian kelulusan',
  },
};
