# Changelog

Setiap update wajib menambah entri di sini. Yang belum dirilis masuk **Unreleased**.
Saat rilis, pindahkan ke heading versi + tanggal.

## Unreleased

### UI/UX Rounded Full Gradient 1 + HD Stabilizer NDK (patch v1.2.5)
- Warna diganti total ke Gradient 1 #F2E6EE (light) -> #977DFF (purple): paper #F2E6EE, accent #977DFF, dark #00033D, line #E9DDFF. Light & dark theme diperbarui.
- Seluruh UI jadi rounded full: bg_card 24dp, bg_card_hero 28dp, bg_preview 28dp, bg_social 20dp, bg_pill 999dp, button cornerRadius 24dp (pill penuh untuk 50dp height).
- Launcher background ganti #977DFF, splash card modern morphing blob gradient 1 dengan kartu live putih.
- Ilustrasi diregenerate 5 buah style 3D morphing minimal organic fluid blob background putih murni agar rembg presisi: illus_live_editor, illus_hold_preview, illus_export_success, illus_picker, illus_settings - semua WebP lossless RGBA.
- Share ke sosial dipindah ke atas tepat di bawah preview (jangan taruh paling bawah) dengan label Live Label, tap JPG Motion Photo, long-press MP4 fallback.
- Icon-icon interior tetap Material Symbols Rounded tapi tint baru #3A2A5A (dark purple) biar enak dilihat, kontras diperjelas.
- Kontras tinggi diperbaiki deskripsi dan palet hc jadi lebih jelas (#6A4DFF accent).
- HD+Stabilizer diperkuat: Enhance shader denoise 0.82 sharpen 0.46 (sebelumnya 0.55/0.28), bitrate adaptif 8-25 Mbps untuk 1080p, fallback otomatis 1080 -> 720 jika encoder gagal (bug reso 1080 gagal).
- Stabilizer zoom agresif 1.08-1.42 (sebelumnya 1.03-1.25) dan formula maxDev*3.6 agar efek stabilisasi kelihatan jelas di preview.
- MAX_ENHANCE_PIXELS naik dari 2073600 ke 4147200 agar 1080x1920 tetap boleh dengan Bersih aktif.
- Tambah modul NDK C++ `fotolive_hd` (CMake 3.22.1, NDK 27.0.12077973): `NativeHD.enhanceBitmap` bilateral 5x5 + coring sharpen native cepat untuk cover HD, `calcBitrate` native.
- Converter sekarang coba pakai NativeHD dulu kalau tersedia, fallback ke Kotlin bila gagal.
- Build CI install NDK 27.0.12077973 otomatis.


### Share Android 2026 (patch v1.2.4)
- TikTok dan WhatsApp Android 2026 sekarang membaca JPG Motion Photo asli dengan label Live/Motion, tidak butuh MP4 lagi (verifikasi lapangan).
- Tombol TikTok dan WhatsApp di ResultActivity sekarang share JPG Motion Photo (`image/jpeg`) via FileProvider/MediaStore, dengan fallback otomatis ke MP4 untuk app versi lama.
- Tombol Aplikasi lain diubah jadi dialog pilihan: Foto Live JPG (Motion) untuk platform yang sudah support Live label, dan Video MP4 murni untuk IG Story & editor.
- Tahan lama (long-press) pada TikTok/WA untuk paksa kirim MP4 bila dibutuhkan.
- Deskripsi layar hasil diperbarui: jelaskan bahwa WA & TikTok baru baca JPG sebagai Live, IG tetap MP4.


### Engine dan kompatibilitas
- Default output diubah ke portrait 9:16; preset 1080p portrait kini benar-benar 1080x1920.
- Rotasi cover dibuat vendor-safe: frame yang sudah diputar oleh MediaMetadataRetriever tidak diputar dua kali.
- Crop cover dan video memakai target dimensi yang sama, termasuk SOURCE tanpa upscale.
- Dukungan diperluas dari Android 7 (API 24) sampai Android 17 (API 37).
- Toolchain dimutakhirkan ke AGP 9.3, Gradle 9.5, built-in Kotlin, dan dependency stabil terbaru.
- Permission partial media Android 14+ ditangani bersama Photo Picker fallback.

### Timeline dan visual
- Material Slider dihapus dari editor dan diganti timeline thumbnail interaktif.
- Bingkai klip 3 detik dan playhead cover dapat digeser langsung seperti editor video.
- Palet ungu lama diganti graphite, cobalt, dan cyan untuk light/dark mode.
- High Contrast sekarang memakai token theme terpisah, border 2dp, ikon tegas, dan target sentuh 48dp.
- Tiga ilustrasi AI baru ditambahkan untuk editor, instruksi hold, dan status export berhasil.
- Ikon aksi gallery, add, share, dan export ditambahkan.
- Insets edge-to-edge dan display cutout ditangani untuk Android modern.
- Banner update disembunyikan secara default dan hanya tampil jika versi GitHub lebih baru; cek dibatasi sekali per 24 jam saat beranda aktif.

### Distribusi
- Setiap push ke main/master yang lolos test, build, dan verifikasi signing otomatis diterbitkan sebagai GitHub Release.
- Patch version dinaikkan dari tag semver terbaru; pull request tidak membuat release.
- Isi bagian Unreleased ini otomatis menjadi release notes dan CHANGELOG.md ikut dilampirkan.

### Revisi kualitas dan share
- Bug batas array pada block matcher stabilizer diperbaiki; sebelumnya radius maksimum dapat mematikan stabilisasi karena out-of-bounds.
- Hasil encode sekarang diverifikasi ulang dimensi dan durasinya sebelum Motion Photo disimpan.
- Preview layar export mengikuti rasio crop yang dipilih, bukan lagi dipaksa kotak 720x720.
- Settings mendapat rasio, Bersih, dan Stabilizer bawaan.
- Share MP4 langsung ditambahkan untuk TikTok, Instagram Story, WhatsApp/WA Business, dan aplikasi lain.
- Logo sosial memakai path brand resmi dan package routing otomatis dengan fallback aman.
- Seluruh ikon antarmuka dimigrasikan ke Material Symbols Rounded yang konsisten.
- Palet diganti menjadi graphite dan deep teal untuk light/dark mode.
- Ilustrasi dibuat ulang tanpa wajah, diproses rembg AI, dan disimpan sebagai WebP transparan; seluruh PNG dihapus.
- Splash memakai kartu editor portrait baru yang selaras dengan timeline.

## [v1.2.1](https://github.com/xykalnotkel/fotolivemaker/releases/tag/v1.2.1) — 2026-08-14

### Perbaikan inti
- Output non-Samsung sekarang mengikuti tata letak Google Motion Photo 1.0 strict: MP4 tepat di EOF, `Item:Length` dan `Item:Padding` diverifikasi.
- Output Samsung memakai mode hybrid SEF terpisah dan tidak lagi diklaim sebagai strict Google.
- Nama hasil sekarang berakhir `MP.jpg` sesuai pola filename Motion Photo Android.
- Runtime permission tulis ditambahkan untuk ekspor ke DCIM pada Android 8–9.
- Resolusi SOURCE dibatasi 4K dan filter Bersih dibatasi Full HD untuk mencegah kehabisan memori.
- Cache thumbnail picker diganti LRU 20 MiB dan decode stale antar-album dicegah.
- Preview hasil di-downsample dan verifikasi dijalankan berurutan agar heap lebih stabil.

### Custom Roundline UI
- Seluruh ikon antarmuka dan launcher digambar ulang sebagai VectorDrawable custom dengan sudut serta ujung garis rounded.
- Seluruh aset ikon 3D/bitmap lama dihapus; ikon adaptif otomatis mengikuti light/dark theme.
- Ikon aksi diperjelas: tutup, cache, source code, update, hold, dan status selesai.
- Radius kartu, dialog, badge, dan tombol diperkecil agar layout lebih tegas; ikon tetap memakai bahasa visual Roundline.
- High Contrast, Reduce Motion, dan haptic slider kini benar-benar diterapkan.

## [v1.2.0](https://github.com/xykalnotkel/fotolivemaker/releases/tag/v1.2.0) — 2026-08-14

### Identitas & Penamaan
- Rebranding nama aplikasi menjadi **Foto Live**.
- Migrasi namespace & application ID ke `livefoto.xystudio.app`.
- Konfigurasi kunci penandatangan resmi XYStudio (RSA 2048-bit) untuk rilis publik dan mitigasi Google Play Protect.

### Core Engine & Kualitas Gambar
- **Bilateral Filter Edge-Preserving**: Meredam noise mikro tanpa mengaburkan tepi objek.
- **Coring Threshold Sharpening**: Mencegah timbulnya bintik pasir / grain pada permukaan halus (wajah, langit, gradasi).
- **Multi-Block Grid Stabilizer (3x3)**: Menggunakan median outlier rejection untuk mengabaikan pergerakan objek di latar depan dan mengunci getaran tangan secara akurat.
- **Gaussian Trajectory Smoothing**: Memisahkan pergerakan kamera disengaja dari getaran tangan.
- **Selector Aspect Ratio Lengkap**: Mendukung rasio Asli, 9:16 (Layar Penuh TikTok/Reels/Story), 3:4 (Portrait), 1:1 (Persegi Feed), 4:3 (Klasik), dan 16:9 (Landscape).
- Penanganan rotasi frame otomatis saat ekstraksi cover JPEG.

### UI/UX & Interaksi
- **Tema Royal Indigo & Electric Violet**: Desain modern dengan palet warna berkelas dan kontras optimal.
- **Seamless Hero Gradient**: Kartu Project Baru di beranda luruh menyatu 100% mulus ke latar belakang.
- **Custom Modals Squircle**: Dialog pemilihan rasio, resolusi, kualitas JPEG, dan tema dengan tampilan modern dan umpan balik haptic.
- **Floating Update Banner 3D**: Banner update mengambang dengan visual 3D morphing organic fluid beresolusi tinggi.
- **Estimasi Waktu Tenang**: Tahapan status ekspor yang informatif dan stabil.

### Aksesibilitas & Pengaturan
- **Mode Kontras Tinggi**: Meningkatkan ketegasan garis batas dan keterbacaan teks.
- **Umpan Balik Getar (Haptics)**: Getaran mikro taktil pada slider dan tombol aksi.
- **Kurangi Animasi (Reduce Motion)**: Opsi mematikan animasi transisi untuk performa instan.

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
