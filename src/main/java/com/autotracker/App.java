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
            cekDiskusiBaru(token, daftarMatkul, courseMap);
        }

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
    // CEK 3: Diskusi Forum (via Forum API)
    // ==========================================

    private static void cekDiskusiBaru(String token, JSONArray daftarMatkul, Map<Integer, String> courseMap) {
        System.out.println("\n💬 [CEK 3] Memeriksa diskusi forum...");
        List<String> diskusiBaru = new ArrayList<>();

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

                    // Filter: hanya Diskusi.X, Kehadiran, dan Tugas
                    if (!MoodleService.isForumRelevan(namaDiskusi)) {
                        System.out.println("🚫 Skip (tidak relevan): " + namaDiskusi);
                        continue;
                    }

                    String penanda = "[DISKUSI] " + namaDiskusi;

                    // Cek Name + Mata Kuliah agar Diskusi.1 dari matkul berbeda tidak saling skip
                    if (NotionService.sudahAda(penanda, namaMatkul)) {
                        System.out.println("⏭️  Skip diskusi: " + namaDiskusi + " | " + namaMatkul);
                        continue;
                    }

                    diskusiBaru.add(namaDiskusi + "\n   📚 " + namaMatkul);
                    System.out.println("💬 Diskusi BARU: " + namaDiskusi + " | " + namaMatkul);
                    NotionService.simpan(penanda, namaMatkul);
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
    }
}