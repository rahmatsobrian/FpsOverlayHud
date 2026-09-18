# Perf HUD — floating FPS/CPU/GPU/RAM overlay

Project Android Studio (Kotlin + Jetpack Compose) untuk overlay HUD performa,
dibuat mengikuti desain di gambar referensi (label berwarna + teks outline
hitam: GPU/MEM hijau, CPU/RAM biru, API/FPS pink+putih, semua nilai oranye).

## ⚠️ Baca dulu — batasan jujur

Saya menulis semua source code di sini, tapi **belum saya compile jadi APK**
karena environment tempat saya kerja tidak punya Android SDK/emulator dan
tidak ada akses internet untuk mengunduh dependency Gradle. Jadi:

- Buka folder ini di **Android Studio (Koala/Ladybug ke atas)**, biarkan
  Gradle sync mengunduh dependency, lalu Run — di situ baru ketahuan kalau
  ada versi library yang perlu disesuaikan (`material3:1.4.0-alpha10`,
  `libsu:6.0.0`, `Shizuku:13.1.5` — cek versi terbaru masing-masing kalau
  ada error "could not resolve").
- **Path sysfs GPU** (`/sys/class/kgsl/kgsl-3d0/...` untuk Adreno,
  `/sys/kernel/gpu/...` untuk Mali) beda-beda per chipset/vendor ROM. Sudah
  saya buat coba beberapa path umum, tapi kemungkinan besar perlu kamu
  tambahkan path spesifik HP kamu (cek dengan `adb shell find /sys -iname "*gpu*busy*"`
  di device yang sudah root/Shizuku-authorized).
- Launcher icon masih vector placeholder sederhana — ganti lewat
  Android Studio → New → Image Asset kalau mau icon final.
- Ini fondasi yang solid dan sudah lengkap secara arsitektur, bukan produk
  jadi yang sudah diuji di banyak device.

## Arsitektur

```
MainActivity          -> layar setting (Compose, Material 3 Expressive)
OverlayService        -> foreground service, bikin window WindowManager,
                          drag-to-move, kunci posisi, loop update stats
OverlayHud.kt          -> Composable HUD-nya sendiri (row GPU/MEM/CPU/RAM/FPS)
OverlayLifecycleOwner -> "adapter" supaya ComposeView bisa hidup di window
                          Service (Service nggak punya Lifecycle bawaan)
stats/
  StatsProvider (interface)  -> kontrak umum: sample(metrics) -> StatsSnapshot
  RootStatsProvider          -> via libsu, baca /proc, /sys, dumpsys gfxinfo
  ShizukuStatsProvider       -> sama seperti Root, tapi lewat Shizuku (non-root)
  NonRootStatsProvider       -> API publik saja (ActivityManager, TrafficStats,
                                 BatteryManager, /proc/stat kalau masih readable)
  FpsTracker                 -> Choreographer, FPS/frame-time/jank
  ForegroundAppDetector      -> UsageStatsManager, buat tahu game apa yang aktif
QuickToggleTileService      -> tile Quick Settings on/off HUD
SettingsRepository          -> DataStore Preferences (semua opsi tersimpan)
ui/theme/                   -> Material 3 (+ Dynamic Color API 31+, fallback
                                 skema statis untuk Android 10-11)
```

## Mode akses (auto-detect + bisa dipaksa manual)

| Mode      | Kelebihan                                   | Kekurangan                                  |
|-----------|----------------------------------------------|----------------------------------------------|
| Root      | Data paling lengkap & akurat (GPU %, FPS asli game via `dumpsys gfxinfo`, suhu per-zone) | Butuh device rooted |
| Shizuku   | Hampir selengkap root, **tanpa root**, cukup aktifkan lewat ADB wireless / app Shizuku sekali | Perlu install app Shizuku + user aktifkan sendiri |
| Non-root  | Jalan di semua device tanpa syarat apa pun   | GPU usage tidak tersedia (dibatasi Android), FPS yang ditampilkan adalah frame overlay kita sendiri, bukan FPS game |

## Fitur yang saya tambahkan di luar yang ada di gambar referensi

- Toggle per-metrik (matikan baris yang tidak perlu)
- Slider ukuran & opacity HUD, HUD bisa di-drag lalu posisinya tersimpan
- Kunci posisi HUD (klik lewat ke game, tidak ke-drag tidak sengaja)
- Battery level + suhu baterai, kecepatan network naik/turun
- Logging sesi ke CSV (`Android/data/.../hud_logs/`) buat dianalisis di Excel/Sheets
- Ringkasan sesi (FPS min/avg/max, jumlah jank, suhu CPU tertinggi) — lihat `OverlayService.buildSessionSummary()`
- Quick Settings Tile buat on/off HUD tanpa buka app
- Auto-detect aplikasi foreground (dasar untuk fitur "auto-show saat buka game" — daftar game bisa kamu perluas di `MainActivity`/settings)
- Dynamic Color (Android 12+) + skema warna statis untuk Android 10-11
- Refresh-rate HUD bisa diatur 250ms–2000ms (hemat baterai vs presisi)

## Yang masih perlu kamu lengkapi

1. Sesuaikan/tambah path sysfs GPU untuk chipset target kamu.
2. Uji izin `SYSTEM_ALERT_WINDOW` + foreground service di Android 14/15 (makin
   ketat tiap versi — mungkin perlu penyesuaian kecil di `startForegroundNotification()`).
3. UI daftar "game apa saja yang trigger auto-show" (backend `ForegroundAppDetector` sudah ada, tinggal buat picker app di MainActivity).
4. Ganti launcher icon vector placeholder dengan aset final.
