# Live Photo Maker (Android)

[![Build APK](https://github.com/xykalnotkel/fotolivemaker/actions/workflows/build.yml/badge.svg)](https://github.com/xykalnotkel/fotolivemaker/actions/workflows/build.yml)
[![License: Apache--2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/xykalnotkel/fotolivemaker?label=APK)](https://github.com/xykalnotkel/fotolivemaker/releases/latest)

Ubah video jadi **Motion Photo** — file yang muncul berlabel **Live** di picker
galeri TikTok saat mau posting.

Video diproses **di perangkat**. Tanpa iklan, tanpa akun, tanpa pelacak.
Banner update tampil offline. Internet hanya saat kamu ketuk update.

Catatan perubahan lengkap ada di **[CHANGELOG.md](CHANGELOG.md)**.

## 📥 Download APK

**[⬇️ Ambil versi terbaru di halaman Releases](https://github.com/xykalnotkel/fotolivemaker/releases/latest)**

Download berkas `.apk` di bagian **Assets**, buka, lalu izinkan
"Install unknown apps" kalau diminta.

---

## Cara pakai

1. Buka app → **Project Baru** (atau ketuk **SISTEM** di pemilih untuk Photo Picker)
2. Atur slider:
   - **Mulai jendela** — bagian video mana yang diambil (jendela 3 detik)
   - **Frame kunci** — foto diamnya yang mana. Preview di atas langsung berubah
3. Durasi klip **dikunci 3,0 detik** (sama dengan Live Photo iPhone). Video yang
   lebih pendek dipakai seluruhnya.
4. Opsional: crop 1:1, pertajam, stabilisasi, atau turunkan ke 720p
5. Tap **EXPORT**
6. File tersimpan ke **DCIM/Camera** dan langsung muncul di galeri

Kamu juga bisa share video dari galeri → pilih **Live Photo Maker**.

### Tes hasilnya
1. Buka **Galeri bawaan** — kalau ada ikon Motion/Live dan bergerak saat ditahan → format sudah benar
2. Buka **TikTok → + → Upload** → pilih fotonya langsung dari galeri. Jangan lewat tombol Bagikan.

Kalau lolos langkah 1 tapi gagal di langkah 2, masalahnya di sisi TikTok
(versi app / region / device), bukan di file-nya.

---

## Rilis publik

Push ke `main` hanya menjalankan tes + mengunggah artifact APK.

Release GitHub dibuat jika:

- kamu push **tag** `v1.1.0` (nama versi diambil dari tag), atau
- kamu tekan **Actions → Build APK → Run workflow** (pakai kunci rilis)

```
git tag v1.1.0
git push origin v1.1.0
```

APK rilis ditandatangani **kunci rilis** dari GitHub Secrets. Jangan unggah
ke Play Store tanpa identitas, kebijakan, dan keystore milikmu sendiri.

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
- **Artifacts** → **LivePhotoMaker-APK**
- Extract, install. HP akan minta izin "Install unknown apps"

---

## Kenapa tidak ada file `gradle-wrapper.jar`?

File itu biner dan susah di-upload dari HP. Workflow memakai
`gradle/actions/setup-gradle` yang meng-install Gradle di runner.

---

## Isi project

```
app/src/main/java/com/arena/motionphoto/
  MotionPhotoWriter.kt   # XMP GCamera + trailer Samsung SEF
  Converter.kt           # trim, encode Media3, simpan DCIM/Camera
  Stabilizer.kt          # analisis guncangan + shader
  MainActivity.kt        # editor: slider jendela + frame kunci
  ProcessActivity.kt     # export, ETA realtime, progress kotak
  ResultActivity.kt      # tahan-untuk-putar + self-test
  PickerActivity.kt      # grid video + Photo Picker sistem
```

### Catatan teknis
- **Media3 Transformer** untuk trim/transcode — hardware accelerated
- Output **H.264 + AAC**, dimensi selalu genap
- Disimpan lewat **MediaStore** ke `DCIM/Camera`
- Dual format: XMP Google + trailer Samsung `MotionPhoto_Data`
- minSdk 26 (Android 8.0), targetSdk 34
- Splash animasi hanya di peluncuran pertama

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

Izin `INTERNET` ada di paket (syarat Android), **tidak dipakai** sampai
kamu ketuk banner update. Video tidak pernah diunggah. Cek `AndroidManifest.xml`.

**Tidak berafiliasi** dengan TikTok, ByteDance, Apple, Google, maupun Samsung.
"Live Photo" merek dagang Apple Inc. · "TikTok" merek dagang ByteDance Ltd. ·
"Motion Photo" dan "Android" merek dagang Google LLC. Dipakai hanya untuk
keperluan deskriptif.
