# 🚀 Auto Tracker Tugas - Universitas Terbuka

Bot otomatis berbasis Java untuk memantau pembaruan tugas dan aktivitas di portal e-learning Universitas Terbuka secara berkala.

## ✨ Fitur Utama
- **Auto Check:** Mengecek tugas secara otomatis dalam interval waktu tertentu (default: 6 jam).
- **Secure Credentials:** Menggunakan sistem `.env` untuk menjaga keamanan NIM dan Password.
- **Background Process:** Menggunakan `ScheduledExecutorService` agar berjalan efisien di background.

## 🛠️ Teknologi yang Digunakan
- **Java 25** (OpenJDK)
- **Maven** (Project Management)
- **Dotenv-Java** (Environment Variables)
- **Java HttpClient** (API Connection)

## 🚀 Cara Penggunaan

1. **Clone Repository ini:**
   ```bash
   git clone [https://github.com/username-kamu/task-tracker.git](https://github.com/username-kamu/task-tracker.git)

2. **SetUp & Konfigurasi:**
   Agar bot bisa login ke e-learning, Anda perlu menyiapkan kredensial:

- Copy file .env.example dan ubah namanya menjadi .env.

- Buka file .env tersebut, lalu masukkan NIM dan Password UT Anda:

    **Plaintext**
    ```bash
    UT_NIM=123456789
    UT_PASS=password_anda

- Catatan: File .env ini aman dan tidak akan ter-upload ke GitHub karena sudah terdaftar di .ignore.

3. **Build & Run:**
- Buka folder task-tracker di IntelliJ IDEA.

- Tunggu IntelliJ mendownload dependencies Maven secara otomatis.

- Klik kanan pada file src/main/java/com/autotracker/App.java.

- Pilih Run 'App.main()'.

- Pantau log di terminal untuk melihat aktivitas bot.
