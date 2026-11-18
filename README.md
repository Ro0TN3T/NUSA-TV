# NUSA TV

## Update channel tanpa build APK

Aplikasi membaca daftar channel dari file `playlist.m3u` pada branch `main` repository ini.

Untuk mengubah channel: buka `playlist.m3u` di GitHub, tekan Edit, ubah/tambah/hapus entry, lalu Commit changes. Buka NUSA TV dan tekan tombol **Perbarui**. Perubahan playlist tidak memerlukan build APK.

Format entry:

```m3u
#EXTINF:-1 tvg-id="CHANNEL.ID" tvg-name="Nama Channel" tvg-logo="https://alamat-logo.png" group-title="Indonesia",Nama Channel
https://alamat-stream-publik.m3u8
```

Gunakan URL HLS publik yang langsung menuju stream dan memang boleh digunakan. NUSA TV memutar stream melalui ExoPlayer di dalam aplikasi. Jika GitHub tidak dapat diakses atau playlist kosong, aplikasi menampilkan status gagal; tidak ada daftar channel lama yang tertanam sebagai fallback.

Source aplikasi yang berubah tetap memerlukan build APK baru. Perubahan `playlist.m3u` saja tidak.

## Dukungan Android TV
- Perangkat Android TV menggunakan orientasi landscape dan layout khusus layar lebar (sw600dp).
- Daftar channel memakai 3 kolom dengan ukuran item dan logo lebih besar.
- Item channel dan tombol memiliki target fokus untuk navigasi D-pad/remote (atas, bawah, kiri, kanan, OK).
- Tombol Back pada remote keluar dari mode layar penuh kembali ke daftar channel.
- HP tetap menggunakan layout potret dan grid 2 kolom.
