# MangaWebTranslator

Aplikasi Android untuk membaca manga dari web dan menerjemahkan balon teks secara langsung di layar secara offline menggunakan Google ML Kit (Text Recognition & On-Device Translation).

## 🚀 Fitur Utama
- **In-App WebView Browser**: Dilengkapi fitur navigasi lancar, User-Agent Chrome modern, penanganan mixed content, dan dukungan third-party cookies untuk CDN gambar manga.
- **Deteksi Teks Otomatis**: Mendukung pengenalan karakter Bahasa Jepang, Hangul Korea, dan Latin/Inggris.
- **Penerjemahan On-Device**: Model terjemahan Google ML Kit diunduh sekali dan berjalan langsung di perangkat ke Bahasa Indonesia tanpa API berbayar.
- **Overlay Dinamis**:
  - Auto-sizing ukuran font teks terjemahan (36f hingga minimal 16f).
  - Background putih solid dengan sudut melengkung dan garis tepi hitam yang otomatis menyesuaikan tinggi teks.
  - Sentuhan scroll tembus pandang (*transparent touch-through*) sehingga pengalaman navigasi webview tidak terganggu.
- **Navigasi Cerdas**:
  - Tombol Floating Action Button (FAB) untuk sembunyikan bilah atas (mode fullscreen).
  - FAB Clear untuk membersihkan terjemahan layar.
  - FAB Translate untuk memindai tampilan saat ini.
  - Otomatis menghapus kotak terjemahan saat halaman web di-scroll lebih dari 10px atau layar diputar.

## 🛠️ Spesifikasi Teknis
- **Bahasa**: Kotlin (100% Kotlin DSL `build.gradle.kts`)
- **Min SDK**: 26 (Android 8.0 Oreo)
- **Target SDK & Compile SDK**: 34 (Android 14)
- **Java / JVM Target**: 17
- **Gradle Version**: 8.4
- **Android Gradle Plugin**: 8.2.2

## 📦 Cara Build Menjadi APK

### 1. Build Otomatis via GitHub Actions (Rekomendasi)
Proyek ini sudah dilengkapi dengan workflow GitHub Actions di `.github/workflows/build-apk.yml`.
1. Push repository ini ke GitHub:
   ```bash
   git add .
   git commit -m "Initial commit MangaWebTranslator"
   git branch -M main
   git remote add origin <URL_REPOSITORY_ANDA>
   git push -u origin main
   ```
2. Buka tab **Actions** di repositori GitHub Anda.
3. Workflow **Build Android APK** akan berjalan otomatis.
4. Setelah selesai, unduh file APK dari bagian **Artifacts** dengan nama **`MangaWebTranslator-APK`**.

### 2. Build Lokal
Pastikan komputer memiliki JDK 17 dan Android SDK:
```bash
# Windows
.\gradlew.bat assembleDebug

# Linux / macOS
./gradlew assembleDebug
```
File APK debug akan dihasilkan di:
`app/build/outputs/apk/debug/app-debug.apk`
