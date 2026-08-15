# Foto Live

[![Build APK](https://github.com/xykalnotkel/fotolivemaker/actions/workflows/build.yml/badge.svg)](https://github.com/xykalnotkel/fotolivemaker/actions/workflows/build.yml)
[![License: XySpace Personal Use](https://img.shields.io/badge/License-Personal%20Use-6A4DFF)](LICENSE)
[![Release](https://img.shields.io/github/v/release/xykalnotkel/fotolivemaker?label=APK)](https://github.com/xykalnotkel/fotolivemaker/releases/latest)
[![Android 7-17](https://img.shields.io/badge/Android-7--17-2563EB)](app/build.gradle.kts)
[![Kotlin](https://img.shields.io/badge/Kotlin-built--in-0F172A)](app/src/main/java/livefoto/xyspace/app)

Ubah video menjadi **Motion Photo** -- file yang muncul berlabel LIVE di picker
galeri TikTok, WhatsApp, dan aplikasi lain saat hendak mengunggah.

Video diproses **sepenuhnya di perangkat**. Tidak ada iklan, tidak perlu akun,
tidak ada pelacak. Cek versi berjalan saat beranda aktif, maksimal sekali
per 24 jam. Banner update hanya muncul bila GitHub memiliki versi lebih baru.

Catatan perubahan lengkap di [CHANGELOG.md](CHANGELOG.md).

---

## Download APK

**[Ambil versi terbaru di halaman Releases](https://github.com/xykalnotkel/fotolivemaker/releases/latest)**

Download file `.apk` pada bagian Assets, buka, lalu izinkan
Install unknown apps bila diminta.

---

## Cara Pakai

1. Buka aplikasi dan ketuk **Project Baru** (atau ketuk SISTEM pada pemilih
   untuk membuka Photo Picker sistem).
2. Atur timeline editor:
   - Geser **bingkai biru** untuk memilih jendela video 3 detik.
   - Geser **garis putih** untuk memilih frame cover. Preview berubah langsung.
3. Output baru menggunakan format portrait **9:16** secara default. Pilihan
   rasio lain tetap tersedia (3:4, 1:1, 4:3, 16:9, dan rasio asli).
4. Durasi klip dikunci 3,0 detik; video yang lebih pendek dipakai seluruhnya.
5. Opsional: ubah rasio, aktifkan Bersih/Stabil, atau pilih resolusi
   720p / 1080p / 2K / 4K / SOURCE.
6. Tap **EXPORT**.
7. File tersimpan ke **DCIM/Camera** dan langsung muncul di galeri.

Kamu juga bisa share video dari galeri dengan memilih aplikasi **Foto Live**.

Di layar hasil tersedia tombol TikTok, Instagram Story, WhatsApp/WA Business,
dan aplikasi lain. Untuk label LIVE, pilih Motion Photo langsung dari picker
aplikasi tujuan.

### Panduan Pengujian

1. Buka **Galeri bawaan** -- jika ada ikon Motion/Live dan bergerak saat
   ditahan, format sudah benar.
2. Buka **TikTok** -> **+** -> **Upload** -> pilih langsung dari galeri
   (jangan lewat tombol Bagikan).

Jika lolos langkah 1 tetapi gagal di langkah 2, penyebabnya ada di sisi TikTok
(versi aplikasi / region / perangkat), bukan di file hasil.

---

## Rilis Publik

Setiap push ke `main` atau `master` yang lolos seluruh tahap (tes unit,
build, verifikasi tanda tangan) langsung menjadi GitHub Release.

- Nomor patch dinaikkan otomatis dari tag semver terbaru.
- Bagian Unreleased dari CHANGELOG.md menjadi deskripsi release.
- CHANGELOG.md lengkap ikut dilampirkan sebagai asset.
- Pull request hanya divalidasi dan tidak membuat release.
- Push tag `v*` tetap dapat dipakai untuk menentukan versi secara manual.
- Workflow manual (Actions -> Run workflow) juga membuat release baru.

APK publik hanya diterbitkan bila ditandatangani kunci rilis dari GitHub
Secrets. Build dengan debug key tetap menjadi artifact internal.

---

## Cara Build Sendiri (Tanpa Komputer)

### 1. Buat Repo di GitHub

Buka github.com lewat browser HP, klik **+** -> **New repository**.
Nama bebas, pilih **Public**, klik **Create repository**.

### 2. Upload File

Di halaman repo -> **Add file** -> **Upload files** -> pilih semua isi folder.
Struktur folder harus dipertahankan. Alternatif: pakai **Termux** atau
tekan tombol titik (.) di URL repo untuk membuka **github.dev**.

### 3. Jalankan Build

Buka tab **Actions** -> enable workflow -> pilih **Build APK** ->
**Run workflow**. Tunggu sekitar 5-10 menit.

### 4. Download APK

Klik run yang selesai -> **Artifacts** -> **FotoLive-APK** -> extract, install.

---

## Isi Proyek

```
app/src/main/java/livefoto/xyspace/app/
  Converter.kt            # Facade utama (delegate ke specialist)
  VideoMath.kt            # Geometri, dimensi, rotasi, slider
  BitmapProcessor.kt      # Filter bilateral, crop, orient, extract frame
  VideoEncoder.kt         # Transcode H.264 + AAC via Media3 Transformer
  MediaStoreWriter.kt     # Simpan ke galeri via MediaStore
  MotionPhotoWriter.kt    # Google Motion Photo 1.0 + Samsung SEF
  Stabilizer.kt           # Analisis multi-block + Gaussian smoothing
  MainActivity.kt         # Editor timeline + frame cover
  ProcessActivity.kt      # Export + progress realtime
  ResultActivity.kt       # Preview tahan-untuk-putar + self-test
  HomeActivity.kt         # Beranda + daftar riwayat
  VideoEditorActivity.kt  # Editor video terpisah (HD/UHD/MP4 murni)
  SettingsActivity.kt     # Pengaturan tema, aksesibilitas, update
```

### Catatan Teknis

- **Media3 Transformer** untuk trim/transcode -- hardware accelerated.
- Output **H.264 + AAC**, dimensi selalu genap.
- Disimpan lewat **MediaStore** ke DCIM/Camera.
- Layout Google Motion Photo 1.0 untuk perangkat umum; hybrid SEF pada Samsung.
- Nama hasil mengikuti pola `...MP.jpg` sesuai spesifikasi Android.
- Output SOURCE dibatasi maksimal 4K; filter Bersih dibatasi Full HD.
- minSdk 24 (Android 7.0), compile/targetSdk 37 (Android 17).
- Ikon antarmuka memakai Material Symbols Rounded; logo sosial memakai path
  brand resmi.
- Ilustrasi transparan diproses dengan rembg AI, disimpan sebagai WebP.
- Hasil encoder diverifikasi ulang dimensi dan durasinya sebelum disimpan.

---

## Lisensi

[**XSPACE PERSONAL USE LICENSE v1.0**](LICENSE) --
Copyright 2026 XySpace -- Haekal Saputra (KALL)

- Source gratis untuk **personal use non-komersial**.
- Dilarang menjual, menerbitkan ulang secara komersial, atau menghapus
  atribusi.
- Disediakan SEBAGAIMANA ADANYA tanpa jaminan.

Library pihak ketiga (AndroidX, Material, Media3, Kotlin, dll) tunduk pada
lisensi masing-masing; lihat [NOTICE](NOTICE) untuk detail.

Ketentuan penggunaan dan batasan tanggung jawab di [TERMS.md](TERMS.md).
Kebijakan privasi di [PRIVACY.md](PRIVACY.md).

---

## Afiliasi

Tidak berafiliasi dengan TikTok, ByteDance, Instagram, WhatsApp, Meta, Apple,
Google, maupun Samsung.

- Live Photo adalah merek dagang Apple Inc.
- TikTok adalah merek dagang ByteDance Ltd.
- Motion Photo dan Android adalah merek dagang Google LLC.

Nama tersebut dipakai hanya untuk keperluan deskriptif.

---

Built with passion by XySpace (Haekal Saputra) -- 2026