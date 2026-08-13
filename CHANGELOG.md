# Changelog

## Unreleased → tag `v1.1.0` (setelah kamu push)

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
