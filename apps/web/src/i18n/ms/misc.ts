import type { Shape } from '../shape';
import type { misc as en } from '../en/misc';

export const misc: Shape<typeof en> = {
  people: {
    scopeNote: {
      SELF: 'Anda boleh melihat rekod anda sendiri.',
      TEAM: 'Anda boleh melihat diri anda, orang bawahan langsung anda dan mereka yang anda menjadi pelulus pengganti.',
      DEPARTMENT: 'Anda boleh melihat semua orang dalam jabatan anda.',
      ENTITY: 'Anda boleh melihat semua orang dalam entiti sah anda.',
      TENANT: 'Anda boleh melihat semua orang dalam syarikat.',
    },
    directory: 'Direktori',
    search: 'Cari nama atau nombor pekerja',
    noMatch: 'Tiada padanan untuk “{{q}}”',
    noOne: 'Tiada sesiapa untuk dipaparkan',
    tryPart: 'Cuba sebahagian nama atau nombor pekerja.',
  },
  reports: {
    noneTitle: 'Belum ada laporan',
    noneBody: 'Laporan muncul di sini untuk modul yang telah dihidupkan oleh syarikat anda.',
    subtitle: 'Baki cuti dan hari yang diambil bagi kakitangan yang anda boleh laporkan.',
    export: 'Eksport CSV', exporting: 'Mengeksport…',
    allTypes: 'Semua jenis cuti', department: 'Jabatan', allDepts: 'Semua jabatan', noDept: 'Tiada jabatan',
    csvHint: 'Eksport CSV merangkumi setiap jabatan dalam skop anda.',
    byPerson: 'Cuti mengikut individu', person: 'Individu', carried: 'Dibawa', left: 'Baki',
    nobody: 'Tiada sesiapa untuk penapis ini.',
    filters: 'Penapis laporan', searchPlaceholder: 'Cari nama, nombor, jabatan atau jenis cuti',
    balance: 'Baki', balanceAny: 'Sebarang baki',
    balanceOpt: { over: 'Melebihi baki', low: 'Kurang daripada {{n}} hari berbaki', unused: 'Belum mengambil cuti', waiting: 'Ada hari menunggu' },
    claims: {
      subtitle: 'Tuntutan diluluskan dan menunggu mengikut tarikh perbelanjaan, bagi kakitangan yang anda boleh laporkan.',
      searchPlaceholder: 'Cari nama, nombor, jabatan atau jenis tuntutan',
      month: 'Bulan', wholeYear: 'Sepanjang tahun',
      show: 'Tunjukkan', showAny: 'Semua', showOpt: { toPay: 'Ada yang perlu dibayar', waiting: 'Tuntutan menunggu', paid: 'Tuntutan dibayar' },
      approved: 'Diluluskan', toPay: 'Untuk dibayar', claims: 'Tuntutan',
      waitingNote: 'Belum diputuskan', toPayNote: 'Diluluskan, belum ditanda dibayar',
      byPerson: 'Tuntutan mengikut individu', byType: 'Mengikut jenis tuntutan', byMonth: 'Mengikut bulan',
      typeMeta: '{{claims}} tuntutan · {{people}} orang',
      none: 'Tiada tuntutan untuk penapis ini.',
      csvHint: 'CSV mengikut tahun, bulan dan jenis tuntutan, untuk semua orang yang anda boleh laporkan.',
    },
  },
};
