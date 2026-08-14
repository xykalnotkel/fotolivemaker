# Foto Live (Android)

[![Build APK](https://github.com/xykalnotkel/fotolivemaker/actions/workflows/build.yml/badge.svg)](https://github.com/xykalnotkel/fotolivemaker/actions/workflows/build.yml)
[![License: Apache--2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/xykalnotkel/fotolivemaker?label=APK)](https://github.com/xykalnotkel/fotolivemaker/releases/latest)
[![Android 7–17](https://img.shields.io/badge/Android-7--17-2563EB)](app/build.gradle.kts)
[![Kotlin](https://img.shields.io/badge/Kotlin-built--in-0F172A)](app/src/main/java)

Ubah video jadi **Motion Photo** — file yang muncul berlabel **Live** di picker
galeri TikTok saat mau posting.

Video diproses **di perangkat**. Tanpa iklan, tanpa akun, tanpa pelacak.
Cek versi berjalan saat beranda aktif, maksimal sekali per 24 jam. Banner hanya
muncul bila GitHub memiliki versi yang lebih baru.

Catatan perubahan lengkap ada di **[CHANGELOG.md](CHANGELOG.md)**.

## Download APK

**[Ambil versi terbaru di halaman Releases](https://github.com/xykalnotkel/fotolivemaker/releases/latest)**

Download berkas `.apk` di bagian **Assets**, buka, lalu izinkan
"Install unknown apps" kalau diminta.

---

## Cara pakai

1. Buka app → **Project Baru** (atau ketuk **SISTEM** di pemilih untuk Photo Picker)
2. Atur timeline editor:
   - Geser **bingkai biru** untuk memilih jendela video 3 detik
   - Geser **garis putih** untuk memilih frame cover. Preview langsung berubah
3. Output baru memakai format portrait **9:16** secara default. Pilihan rasio lain tetap tersedia.
4. Durasi klip dikunci 3,0 detik; video yang lebih pendek dipakai seluruhnya.
5. Opsional: ubah rasio, aktifkan Bersih/Stabil, atau pilih 720p/1080p/SOURCE
6. Tap **EXPORT**
7. File tersimpan ke **DCIM/Camera** dan langsung muncul di galeri

Kamu juga bisa share video dari galeri → pilih **Foto Live**.

### Tes hasilnya
1. Buka **Galeri bawaan** — kalau ada ikon Motion/Live dan bergerak saat ditahan → format sudah benar
2. Buka **TikTok → + → Upload** → pilih fotonya langsung dari galeri. Jangan lewat tombol Bagikan.

Kalau lolos langkah 1 tapi gagal di langkah 2, masalahnya di sisi TikTok
(versi app / region / device), bukan di file-nya.

---

## Rilis publik

Setiap push ke `main` atau `master` menjalankan test, build, pemeriksaan tanda
tangan, lalu langsung membuat GitHub Release jika seluruh tahap sukses.

- Nomor patch dinaikkan otomatis dari tag semver terbaru.
- Bagian `Unreleased` dari `CHANGELOG.md` menjadi deskripsi release.
- `CHANGELOG.md` lengkap ikut dilampirkan sebagai asset.
- Pull request hanya divalidasi dan tidak membuat release.
- Push tag `v*` tetap dapat dipakai untuk menentukan versi secara manual.
- Workflow manual juga membuat release baru.

APK publik hanya diterbitkan bila ditandatangani kunci rilis dari GitHub
Secrets. Build dengan debug key tetap menjadi artifact internal dan tidak
pernah dipublikasikan sebagai release.

---

## Cara build sendiri tanpa komputer

### 1. Bikin repo di GitHub
- Buka github.com lewat browser HP → login
- Klik **+** → **New repository**
- Nama bebas, misal `live-photo-maker`
- Pilih **Public** (Actions gratis unlimited untuk repo public)
- Klik **Create repository**

### 2. Upload file-file ini
Di halaman repo → **Add file** → **Upload files** → pilih semua isi folder ini.

> **Penting:** struktur folder harus dipertahankan. Kalau upload lewat browser HP
> susah menjaga folder, cara paling gampang: install app **Termux** atau
> pakai fitur **github.dev** (buka repo → tekan tombol titik `.` di URL).

### 3. Jalankan build
- Buka tab **Actions** → enable workflow
- Pilih **Build APK** → **Run workflow**
- Tunggu ±5–10 menit

### 4. Download APK
- Klik run yang sudah selesai
- **Artifacts** → **FotoLive-APK**
- Extract, install. HP akan minta izin "Install unknown apps"

---

## Kenapa tidak ada file `gradle-wrapper.jar`?

File itu biner dan susah di-upload dari HP. Workflow memakai
`gradle/actions/setup-gradle` yang meng-install Gradle di runner.

---

## Isi project

```
app/src/main/java/livefoto/xystudio/app/
  MotionPhotoWriter.kt   # Google Motion Photo 1.0 + mode hybrid Samsung SEF
  Converter.kt           # trim, encode Media3, simpan DCIM/Camera
  Stabilizer.kt          # analisis guncangan + shader
  MainActivity.kt        # editor timeline + frame cover
  ProcessActivity.kt     # export, ETA realtime, progress kotak
  ResultActivity.kt      # tahan-untuk-putar + self-test
  PickerActivity.kt      # grid video + Photo Picker sistem
```

### Catatan teknis
- **Media3 Transformer** untuk trim/transcode — hardware accelerated
- Output **H.264 + AAC**, dimensi selalu genap
- Disimpan lewat **MediaStore** ke `DCIM/Camera`
- Layout Google Motion Photo 1.0 pada perangkat umum; hybrid SEF pada Samsung
- Nama hasil mengikuti pola `...MP.jpg` yang disarankan spesifikasi Android
- Output SOURCE dibatasi maksimal 4K; filter Bersih dibatasi Full HD agar aman dari OOM
- minSdk 24 (Android 7.0), compile/targetSdk 37 (Android 17)
- Splash animasi dapat dimatikan lewat Pengaturan

### Kalau build gagal
Buka run yang merah di tab Actions. Kalau step "Tes unit" gagal, laporan
ada di artifact **laporan-tes**.

---

## Lisensi & ketentuan

- **[LICENSE](LICENSE)** — Apache License 2.0 · Copyright 2026 XyStudio — Haekal Saputra
- **[NOTICE](NOTICE)** — pemberitahuan lisensi library pihak ketiga
- **[TERMS.md](TERMS.md)** — syarat penggunaan & batasan tanggung jawab
- **[PRIVACY.md](PRIVACY.md)** — kebijakan privasi

**Ringkasnya:** gratis, open source, disediakan **apa adanya tanpa jaminan**.

Izin `INTERNET` dipakai untuk cek versi GitHub saat beranda aktif (maksimal
sekali per 24 jam) dan mengunduh APK setelah kamu mengetuk banner. Tidak ada
background worker dan video tidak pernah diunggah. Cek `AndroidManifest.xml`.

**Tidak berafiliasi** dengan TikTok, ByteDance, Apple, Google, maupun Samsung.
"Live Photo" merek dagang Apple Inc. · "TikTok" merek dagang ByteDance Ltd. ·
"Motion Photo" dan "Android" merek dagang Google LLC. Dipakai hanya untuk
keperluan deskriptif.
