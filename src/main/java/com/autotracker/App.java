package com.autotracker;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Entry point utama bot tracker e-learning UT.
 * Orkestrasi 3 pengecekan: Matkul Baru, Tugas/Kuis, dan Diskusi Forum.
 */
public class App {

    public static void main(String[] args) {
        System.out.println("\n⏳ [" + java.time.LocalTime.now() + "] Bot bangun! Mengecek e-learning...");

        String token = MoodleService.getToken();
        if (token == null) { System.out.println("❌ Gagal login. Bot berhenti."); return; }

        int userId = MoodleService.getUserId(token);
        if (userId == -1) { System.out.println("❌ Gagal ambil profil. Bot berhenti."); return; }

        JSONArray daftarMatkul = MoodleService.getDaftarMatkul(token, userId);
        if (daftarMatkul == null || daftarMatkul.isEmpty()) {
            System.out.println("📭 Belum ada mata kuliah aktif. Bot tidur.");
        } else {
            // Buat peta courseId -> namaMatkul untuk lookup cepat di semua pengecekan
            Map<Integer, String> courseMap = MoodleService.buildCourseMap(daftarMatkul);

            cekMatkulBaru(daftarMatkul);
            cekTugasBaru(token, daftarMatkul, courseMap);
            cekDiskusiBaru(token, daftarMatkul, courseMap, userId);
        }

        // Selalu kirim laporan periodik setiap run — sebagai pengingat rutin
        kirimRingkasanPeriodik();

        System.out.println("\n💤 Pengecekan selesai. Bot tidur lagi...");
    }

    // ==========================================
    // CEK 1: Matkul Baru (awal semester)
    // ==========================================

    private static void cekMatkulBaru(JSONArray daftarMatkul) {
        System.out.println("\n📋 [CEK 1] Memeriksa matkul baru...");
        List<String> matkulBaru = new ArrayList<>();

        for (int i = 0; i < daftarMatkul.length(); i++) {
            JSONObject matkul = daftarMatkul.getJSONObject(i);
            String nama = matkul.getString("fullname");
            String penanda = "[MATKUL] " + nama;

            if (!NotionService.sudahAda(penanda)) {
                matkulBaru.add(nama);
                System.out.println("🆕 Matkul BARU: " + nama);
                NotionService.simpan(penanda, nama);
            } else {
                System.out.println("⏭️  Skip matkul: " + nama);
            }
        }

        if (!matkulBaru.isEmpty()) {
            StringBuilder pesan = new StringBuilder("📚 Semester Baru! " + matkulBaru.size() + " matkul aktif:\n");
            for (String m : matkulBaru) pesan.append("• ").append(m).append("\n");
            TelegramService.kirim(pesan.toString().trim());
        }
    }

    // ==========================================
    // CEK 2: Tugas & Kuis (via Calendar API)
    // ==========================================

    private static void cekTugasBaru(String token, JSONArray daftarMatkul, Map<Integer, String> courseMap) {
        System.out.println("\n📡 [CEK 2] Memeriksa tugas & kuis...");
        List<String> tugasBaru = new ArrayList<>();

        try {
            JSONArray events = MoodleService.getCalendarEvents(token, daftarMatkul);

            if (events.isEmpty()) {
                System.out.println("📭 Belum ada tugas/kuis mendatang.");
                return;
            }

            System.out.println("🔍 Ditemukan " + events.length() + " event. Mengecek ke Notion...");
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                String namaTugas = event.getString("name");
                int courseId = event.getJSONObject("course").getInt("id");
                String namaMatkul = courseMap.getOrDefault(courseId, "Matkul Tidak Diketahui");

                if (NotionService.sudahAda(namaTugas, namaMatkul)) {
                    System.out.println("⏭️  Skip tugas: " + namaTugas);
                    continue;
                }

                tugasBaru.add(namaTugas + "\n   📚 " + namaMatkul);
                System.out.println("👉 Tugas BARU: " + namaTugas + " | " + namaMatkul);
                NotionService.simpan(namaTugas, namaMatkul);
            }
        } catch (Exception e) {
            System.out.println("❌ Gagal cek tugas: " + e.getMessage());
        }

        if (!tugasBaru.isEmpty()) {
            StringBuilder pesan = new StringBuilder("🚨 Ada " + tugasBaru.size() + " Tugas/Kuis BARU!\n\n");
            for (String t : tugasBaru) pesan.append("• ").append(t).append("\n");
            TelegramService.kirim(pesan.toString().trim());
        } else {
            System.out.println("💤 Tidak ada tugas baru.");
        }
    }

    // ==========================================
    // LAPORAN PERIODIK (selalu jalan tiap run)
    // ==========================================

    /**
     * Selalu kirim summary ke Telegram setiap bot jalan.
     * Berisi daftar diskusi yang masih belum dikerjakan sebagai pengingat.
     */
    private static void kirimRingkasanPeriodik() {
        System.out.println("\n📊 [LAPORAN] Menyiapkan ringkasan periodik...");
        List<String> pending = NotionService.getPendingDiskusi();

        StringBuilder pesan = new StringBuilder();
        pesan.append("📊 Laporan Bot UT | ").append(java.time.LocalDate.now()).append("\n");
        pesan.append("\u23f0 ").append(java.time.LocalTime.now().withNano(0)).append("\n\n");

        if (pending.isEmpty()) {
            pesan.append("✅ Semua diskusi sudah selesai! Mantap bro, gak ada yg tertinggal!");
        } else {
            pesan.append("⏳ ").append(pending.size()).append(" Diskusi belum dikerjakan:\n\n");
            for (String item : pending) {
                pesan.append("• ").append(item).append("\n");
            }
            pesan.append("\n💡 Yuk segera dikerjain sebelum deadline!");
        }

        TelegramService.kirim(pesan.toString().trim());
    }

    // ==========================================
    // CEK 3: Diskusi Forum (via Forum API)
    // ==========================================

    private static void cekDiskusiBaru(String token, JSONArray daftarMatkul, Map<Integer, String> courseMap, int userId) {
        System.out.println("\n💬 [CEK 3] Memeriksa diskusi forum...");
        List<String> diskusiBaru = new ArrayList<>();
        int selesaiDiupdate = 0;

        try {
            JSONArray forums = MoodleService.getForumsByCourses(token, daftarMatkul);

            if (forums.isEmpty()) {
                System.out.println("📭 Tidak ada forum ditemukan.");
                return;
            }

            System.out.println("📂 Ditemukan " + forums.length() + " forum. Mengecek diskusi...");
            for (int f = 0; f < forums.length(); f++) {
                JSONObject forum = forums.getJSONObject(f);
                int forumId = forum.getInt("id");
                int courseId = forum.getInt("course");
                String namaMatkul = courseMap.getOrDefault(courseId, "Matkul Tidak Diketahui");

                JSONArray discussions = MoodleService.getForumDiscussions(token, forumId);
                for (int d = 0; d < discussions.length(); d++) {
                    JSONObject disc = discussions.getJSONObject(d);
                    String namaDiskusi = disc.getString("name");
                    int discussionId = disc.getInt("id");

                    // Filter: hanya Diskusi.X, Kehadiran, dan Tugas
                    if (!MoodleService.isForumRelevan(namaDiskusi)) {
                        System.out.println("🚫 Skip (tidak relevan): " + namaDiskusi);
                        continue;
                    }

                    String penanda = "[DISKUSI] " + namaDiskusi;

                    // Cek apakah user sudah berpartisipasi di diskusi ini
                    boolean sudahDikerjakan = MoodleService.sudahBerpartisipasi(token, discussionId, userId);

                    if (NotionService.sudahAda(penanda, namaMatkul)) {
                        // Sudah tercatat di Notion — cek apakah perlu update status ke Selesai
                        if (sudahDikerjakan) {
                            String pageId = NotionService.getPageId(penanda, namaMatkul);
                            if (pageId != null) {
                                NotionService.tandaiSelesai(pageId);
                                selesaiDiupdate++;
                                System.out.println("✅ Diupdate ke Selesai: " + namaDiskusi + " | " + namaMatkul);
                            }
                        } else {
                            System.out.println("⏭️  Belum dikerjakan, sudah di Notion: " + namaDiskusi);
                        }
                        continue;
                    }

                    // Belum ada di Notion sama sekali
                    if (sudahDikerjakan) {
                        // Sudah dikerjakan tapi belum tercatat — simpan langsung sebagai Selesai
                        System.out.println("✅ Sudah dikerjakan, simpan sebagai Selesai: " + namaDiskusi);
                        String pageId = NotionService.simpanDanAmbilId(penanda, namaMatkul);
                        if (pageId != null) NotionService.tandaiSelesai(pageId);
                    } else {
                        // Diskusi baru dan belum dikerjakan — notif!
                        diskusiBaru.add(namaDiskusi + "\n   📚 " + namaMatkul);
                        System.out.println("💬 Diskusi BARU: " + namaDiskusi + " | " + namaMatkul);
                        NotionService.simpan(penanda, namaMatkul);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("❌ Gagal cek diskusi: " + e.getMessage());
        }

        if (!diskusiBaru.isEmpty()) {
            StringBuilder pesan = new StringBuilder("💬 Ada " + diskusiBaru.size() + " Diskusi BARU!\n\n");
            for (String d : diskusiBaru) pesan.append("• ").append(d).append("\n");
            TelegramService.kirim(pesan.toString().trim());
        } else {
            System.out.println("💤 Tidak ada diskusi baru.");
        }

        if (selesaiDiupdate > 0) {
            System.out.println("📝 " + selesaiDiupdate + " diskusi diupdate ke Selesai di Notion.");
        }
    }
}