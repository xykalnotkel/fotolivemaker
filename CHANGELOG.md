# Changelog

Setiap update wajib menambah entri di sini. Yang belum dirilis masuk **Unreleased**.
Saat rilis, pindahkan ke heading versi + tanggal.

## Unreleased

### Home
- Banner **selalu** tampil tanpa internet: "Cek update"
- Setelah sekali cek (pakai data), cache tahu versi terlewat → "Update terbaru"
- Ketuk + data: unduh di app atau buka web resmi
- Tidak ada cek jaringan di latar belakang
- Cincin 3D krom morphing di tengah, keluar dari bar

### Bersih & stabilizer
- **Bersih** meredam noise di **video + foto**, lalu unsharp ringan supaya tidak empuk
- Urutan dibetulkan: **stabil dulu, baru bersih** (zoom-lah yang bikin buram)
- Kalau Stabil nyala, tajam dipulihkan sedikit lebih kuat
- Stabilizer: geser + **putar ringan**
- Hint: mau filter TikTok, matikan Bersih & Stabil

### Export
- Tombol EXPORT berbingkai kotak
- Progress siku, nempel di tepi preview, mulai pojok kiri atas
- Persentase cuma angka + `%`, tanpa latar
- Preview mulai hitam lalu terang mengikuti proses
- Estimasi bentuk perkiraan (`sekitar 1m 2s`)
- Preview diperkecil (gaya CapCut), bukan memenuhi layar

### Editor
- Tool rail bawah (1:1 / Tajam / Stabil / resolusi) menggantikan toggle
- Label jujur: tajam bukan AI/HD; stabilizer hanya geser X/Y

### Home & riwayat
- Kartu Project Baru lebih pendek, gradien luruh ke background (pekat di atas supaya judul kebaca)
- Hapus per item (ikon tong) + Hapus semua
- Ikon/ilustrasi pakai vector + tint tema (gelap/terang)

### Settings
- Kualitas JPEG, splash on/off, hapus semua hasil
- Catatan jujur soal tajam & stabilizer

### Branding
- Launcher: simbol Live Photo putih di ubin gelap — bukan gunung galeri
- Splash singkat (~0,7 dtk), warna ikuti tema, bisa dimatikan

## [v1.1.0](https://github.com/xykalnotkel/fotolivemaker/releases/tag/v1.1.0) — 2026-08-13

Commit `c23ac54`.

### Layar export
- Preview di tengah, tanpa background / shade hitam
- Progress mengikuti **kotak preview** (bukan cincin)
- Persentase di tengah preview
- Estimasi sisa waktu realtime (berjalan + sisa), di-update tiap 400 ms

### Editor
- Slider **jendela 3 detik** + **frame kunci** dikembalikan
- Durasi dikunci 3,0 dtk (video pendek dipakai utuh)
- Range slider selalu sah — tidak crash di video < 1 dtk

### Perbaikan
- Lisensi disamakan ke **Apache 2.0** (app, CI, TERMS)
- README, PRIVACY, TERMS disesuaikan dengan kode
- Photo Picker sistem sebagai fallback (tombol SISTEM)
- Enhance juga diterapkan ke cover JPEG
- `extractMp4()` bersama, trailer SEF tidak ikut ke preview
- `clearCache()` menghitung yang benar-benar terhapus
- `evenUp` satu fungsi (bulat, bukan potong)
- Splash hanya di peluncuran pertama
- URI Share / Picker diteruskan dengan `clipData`
- Predictive back membatalkan encode
- CI: Release publik hanya dari **tag `v*`** atau **Run workflow**
- Tes memakai fungsi asli (`decideLevel`, `evenUp`, `clipSliders`, `extractMp4`)
