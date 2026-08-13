# Changelog

Setiap update wajib menambah entri di sini. Yang belum dirilis masuk **Unreleased**.
Saat rilis, pindahkan ke heading versi + tanggal.

## Unreleased

### Core Engine & Kualitas Gambar
- Filter bilateral edge-preserving dengan coring threshold: redam noise mikro tanpa memunculkan bintik pasir pada area halus (kulit, langit, gradasi).
- Rotasi frame otomatis (0/90/180/270 derajat) saat ekstraksi cover JPEG dari video sumber.
- Stabilizer ditingkatkan menjadi Multi-Block Grid (3x3) dengan Median Outlier Rejection dan Gaussian Trajectory Smoothing: mengabaikan subjek bergerak di latar depan dan mengunci getaran tangan secara akurat.
- Selector Aspect Ratio lengkap: Asli, 9:16 (Layar Penuh TikTok/Reels), 3:4 (Portrait), 1:1 (Persegi), 4:3 (Klasik), dan 16:9 (Landscape).
- Optimasi unsharp mask di shader OpenGL GLSL dengan smoothstep coring gate.

### Tampilan & Pengalaman Pengguna (UI/UX)
- Palet tema modern dengan aksen Live Gold / Golden Amber, permukaan rounded squircle (14dp-16dp), dan kontras tegas.
- Seluruh icon vector didesain ulang dengan gaya modern: Live Photo concentric rings, aspect ratio framing, AI clarity sparkle, stabilizer gimbal horizon, dan high-tech resolution badge.
- Layar hasil dengan badge LIVE beraksen amber dan mode preview interaktif (Statis, Live, Putar).
- Estimasi waktu export (ETA) disederhanakan menjadi tahapan waktu yang tenang dan informatif.

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
