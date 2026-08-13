# Live Photo Maker (Android)

[![Build APK](https://github.com/xykalnotkel/fotolivemaker/actions/workflows/build.yml/badge.svg)](https://github.com/xykalnotkel/fotolivemaker/actions/workflows/build.yml)
[![License: Apache--2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/xykalnotkel/fotolivemaker?label=APK)](https://github.com/xykalnotkel/fotolivemaker/releases/latest)

Ubah video jadi **Motion Photo** — file yang muncul berlabel **Live** di picker
galeri TikTok saat mau posting.

Berjalan **sepenuhnya offline**. Tanpa iklan, tanpa akun, tanpa pelacak.

## 📥 Download APK

**[⬇️ Ambil versi terbaru di halaman Releases](https://github.com/xykalnotkel/fotolivemaker/releases/latest)**

Download berkas `.apk` di bagian **Assets**, buka, lalu izinkan
"Install unknown apps" kalau diminta.

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
> pakai fitur **github.dev** (buka repo → tekan tombol titik `.` di URL, jadi
> `github.dev`) yang punya editor file lengkap di browser.

Atau paling praktis: zip folder ini, lalu upload & extract lewat github.dev.

### 3. Jalankan build
- Buka tab **Actions** di repo-mu
- Kalau ada tombol "I understand my workflows, enable them" → klik
- Pilih workflow **Build APK** → **Run workflow** → **Run workflow**
- Tunggu ±5–10 menit (build pertama paling lama, berikutnya lebih cepat karena cache)

### 4. Download APK
- Klik run yang sudah selesai (centang hijau)
- Scroll ke bawah → bagian **Artifacts** → **LivePhotoMaker-APK**
- Download, extract zip-nya, install APK-nya
- HP akan minta izin "Install unknown apps" → izinkan

APK sudah ditandatangani dengan debug key, jadi **langsung bisa di-install**
tanpa setup signing apa pun.

---

## Cara pakai app-nya

1. Buka app → **Pilih Video**
2. Atur slider:
   - **Mulai potong** — bagian video mana yang diambil
   - **Durasi klip** — biarkan **3 detik** (paling aman, sama dengan Live Photo iPhone)
   - **Posisi frame kunci** — ini menentukan foto diamnya yang mana.
     Preview di atas langsung berubah saat digeser.
3. Opsional: crop 1:1, atau turunkan ke 720p biar file lebih kecil
4. Tap **Buat Live Photo**
5. File tersimpan ke **DCIM/Camera** dan langsung muncul di galeri

Kamu juga bisa share video dari galeri → pilih **Live Photo Maker**.

### Tes hasilnya
1. Buka **Galeri bawaan** — kalau ada ikon Motion/Live dan bergerak saat ditahan → format sudah benar ✅
2. Buka **TikTok → + → Upload** → cek apakah muncul berlabel Live

Kalau lolos langkah 1 tapi gagal di langkah 2, masalahnya di sisi TikTok
(versi app / region / device), bukan di file-nya.

App menampilkan hasil self-test setelah convert (`=> VALID`), jadi kamu tahu
pasti apakah struktur file-nya benar.

---

## Kenapa tidak ada file `gradle-wrapper.jar`?

File itu biner dan susah di-upload dari HP. Jadi workflow-nya sengaja memakai
`gradle/actions/setup-gradle` yang meng-install Gradle sendiri di runner.
**Semua file di repo ini teks murni** — bisa dibuat/diedit dari HP, termasuk
ikon app yang dibikin pakai vector XML, bukan PNG.

---

## Isi project

```
app/src/main/java/com/arena/motionphoto/
  MotionPhotoWriter.kt   # inti: bikin XMP + gabung JPEG & MP4
  Converter.kt           # ambil frame, trim video, simpan ke galeri
  MainActivity.kt        # UI
app/src/test/java/...
  MotionPhotoWriterTest.kt   # 7 tes format, jalan otomatis sebelum build
.github/workflows/build.yml  # CI: tes -> build -> upload APK
```

### Catatan teknis
- **Media3 Transformer** untuk trim/transcode — hardware accelerated,
  tidak perlu bundling ffmpeg (hemat ~20 MB APK)
- Output **H.264 + AAC**, dimensi selalu dibulatkan ke angka genap
  (dimensi ganjil bikin encoder & parser galeri rewel)
- Disimpan lewat **MediaStore** ke `DCIM/Camera` supaya otomatis ter-index.
  File di folder `Download` tidak akan muncul di picker TikTok.
- minSdk 26 (Android 8.0)

### Kalau build gagal
Buka run yang merah di tab Actions → lihat step mana yang error.
Kalau step "Tes unit" yang gagal, laporan lengkapnya bisa didownload
di artifact **laporan-tes**.

---

## Lisensi & ketentuan

- **[LICENSE](LICENSE)** — Apache License 2.0 · Copyright 2026 XyStudio — Haekal Saputra
- **[NOTICE](NOTICE)** — pemberitahuan lisensi library pihak ketiga
- **[TERMS.md](TERMS.md)** — syarat penggunaan & batasan tanggung jawab
- **[PRIVACY.md](PRIVACY.md)** — kebijakan privasi

**Ringkasnya:** gratis, open source, disediakan **apa adanya tanpa jaminan**.

App ini **tidak punya izin `INTERNET`** sama sekali — bisa kamu cek sendiri di
`AndroidManifest.xml`. Secara teknis app ini tidak mampu mengirim data ke mana pun.

**Tidak berafiliasi** dengan TikTok, ByteDance, Apple, Google, maupun Samsung.
"Live Photo" merek dagang Apple Inc. · "TikTok" merek dagang ByteDance Ltd. ·
"Motion Photo" dan "Android" merek dagang Google LLC. Dipakai hanya untuk
keperluan deskriptif.

> ⚠️ APK ditandatangani **debug key** supaya langsung bisa di-install.
> Jangan diunggah ke Play Store apa adanya — perlu keystore rilis sendiri.
