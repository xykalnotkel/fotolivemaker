# Kebijakan Privasi (Privacy Policy)

**Aplikasi:** Live Photo Maker
**Berlaku sejak:** 13 Agustus 2026

## Ringkasan singkat

Video diproses **di perangkat**. Banner update **tidak** memakai jaringan.
Internet hanya dipakai **saat kamu mengetuk** banner (cek rilis / unduh APK).
Tidak ada analytics, tidak ada unggah file.

---

## Data yang kami kumpulkan

**Tidak ada.**

Aplikasi ini:

- Tidak mengumpulkan data pribadi
- Tidak mengirim video atau file hasil ke mana pun
- Tidak memiliki server, akun, atau login
- Tidak memakai analytics, pelacak, maupun iklan
- Tidak membaca kontak, lokasi, mikrofon, atau kamera
- Tidak menampilkan iklan pihak ketiga

Izin `INTERNET` ada di APK karena Android tidak bisa menambahkannya nanti.
**Tidak ada permintaan jaringan di latar belakang.** Koneksi terjadi hanya
kalau kamu ketuk banner update: `GET` rilis GitHub, lalu (kalau kamu pilih)
unduh berkas `.apk`. Tidak ada data pribadi di permintaan itu.

## Izin yang diminta dan alasannya

| Izin | Kegunaan |
|---|---|
| `INTERNET` | Hanya saat ketuk update: cek/unduh APK dari GitHub |
| `ACCESS_NETWORK_STATE` | Tahu apakah data/Wi-Fi nyala, tanpa mengirim apa pun |
| `REQUEST_INSTALL_PACKAGES` | Memasang APK update yang kamu unduh sendiri |
| `READ_MEDIA_VIDEO` (Android 13+) | Menampilkan kisi video di pemilih internal |
| `READ_EXTERNAL_STORAGE` (Android ≤12) | Sama seperti di atas, untuk versi Android lama |
| `WRITE_EXTERNAL_STORAGE` (Android ≤9) | Menyimpan hasil ke folder DCIM/Camera |

Di Android 10 ke atas, penyimpanan hasil menggunakan **MediaStore**, sehingga
tidak memerlukan izin penyimpanan.

Kalau izin ditolak, atau kamu mengetuk **SISTEM**, aplikasi memakai
**Photo Picker** bawaan Android. Jalur itu hanya memberi akses ke **satu
video yang kamu pilih**, tanpa memindai galeri.

## Apa yang dibaca di perangkat

- **Pemilih internal** (kalau izin diberikan) menanyakan MediaStore daftar
  video agar bisa ditampilkan per album. Aplikasi tidak mengunggah daftar itu
  ke mana pun.
- **Riwayat "Pernah dibuat"** adalah query MediaStore untuk JPEG berawalan
  `MP_` yang aplikasi ini sendiri tulis ke `DCIM/Camera`. Bukan database
  tersembunyi. Kalau kamu hapus file dari galeri, ia hilang dari daftar.
- Pengaturan (resolusi, tema, dsb.) disimpan di SharedPreferences lokal.

## Penyimpanan data

- **File hasil konversi** disimpan di `DCIM/Camera` pada perangkatmu, dan
  sepenuhnya menjadi milikmu.
- **File sementara** dibuat di cache internal selama proses konversi,
  lalu bisa dibersihkan dari Pengaturan.
- Tidak ada cadangan ke cloud.

## Layanan pihak ketiga

Aplikasi ini menggunakan pustaka open source (AndroidX, Media3, Material
Components) yang berjalan lokal di perangkat. **Tidak ada satu pun SDK pihak
ketiga yang mengumpulkan data** yang disertakan.

## Anak-anak

Aplikasi ini tidak mengumpulkan data dari siapa pun, termasuk anak di bawah 13
tahun.

## Perubahan kebijakan

Perubahan akan dipublikasikan di halaman ini pada repositori GitHub.

## Kontak

Untuk pertanyaan atau laporan masalah, silakan buka *issue* di:
https://github.com/xykalnotkel/fotolivemaker/issues

---

*Karena aplikasi ini open source, kamu tidak perlu percaya begitu saja pada
kebijakan ini — silakan periksa sendiri kode sumbernya.*
