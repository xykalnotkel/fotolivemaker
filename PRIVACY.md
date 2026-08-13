# Kebijakan Privasi (Privacy Policy)

**Aplikasi:** Live Photo Maker
**Berlaku sejak:** 13 Agustus 2026

## Ringkasan singkat

**Aplikasi ini tidak mengumpulkan data apa pun darimu. Titik.**

Semua pemrosesan terjadi 100% di dalam perangkatmu, secara offline.

---

## Data yang kami kumpulkan

**Tidak ada.**

Aplikasi ini:
- ❌ Tidak mengumpulkan data pribadi
- ❌ Tidak mengirim file atau data apa pun ke internet
- ❌ Tidak memiliki server, akun, atau login
- ❌ Tidak memakai analytics, pelacak, maupun iklan
- ❌ Tidak membaca kontak, lokasi, mikrofon, atau kamera
- ❌ Tidak menampilkan iklan pihak ketiga

Aplikasi ini bahkan **tidak meminta izin akses internet** (`INTERNET` permission
tidak ada di AndroidManifest), jadi secara teknis aplikasi ini **tidak mampu**
mengirim datamu ke mana pun — ini bisa kamu verifikasi sendiri di kode sumbernya.

## Izin yang diminta dan alasannya

| Izin | Kegunaan |
|---|---|
| `READ_MEDIA_VIDEO` (Android 13+) | Membaca video yang **kamu pilih sendiri** untuk dikonversi |
| `READ_EXTERNAL_STORAGE` (Android ≤12) | Sama seperti di atas, untuk versi Android lama |
| `WRITE_EXTERNAL_STORAGE` (Android ≤9) | Menyimpan hasil ke folder DCIM/Camera |

Di Android 10 ke atas, penyimpanan hasil menggunakan **MediaStore**, sehingga
tidak memerlukan izin penyimpanan sama sekali.

Aplikasi hanya mengakses file video yang **kamu pilih secara eksplisit** lewat
pemilih file sistem. Aplikasi tidak memindai galerimu.

## Penyimpanan data

- **File hasil konversi** disimpan di `DCIM/Camera` pada perangkatmu, dan
  sepenuhnya menjadi milikmu.
- **File sementara** dibuat di cache internal aplikasi selama proses konversi,
  lalu dihapus otomatis setelah selesai.
- Tidak ada database, tidak ada riwayat, tidak ada cadangan ke cloud.

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
