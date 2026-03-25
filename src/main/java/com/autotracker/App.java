package com.autotracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.cdimascio.dotenv.Dotenv;

public class App {
    private static final Dotenv dotenv = Dotenv.load();
    private static final String NIM = dotenv.get("UT_NIM");
    private static final String PASS = dotenv.get("UT_PASS");

    public static void main(String[] args) {
        System.out.println("\n⏳ [" + java.time.LocalTime.now() + "] Bot bangun! Mengecek e-learning...");

        String token = getUtToken();

        if (token != null) {
            // FASE 3: LANGSUNG CEK TUGAS/DISKUSI PENDING
            cekTugasPending(token);
        }

        System.out.println("💤 Pengecekan selesai. Bot tidur lagi...");
    }

    // ==========================================
    // FUNGSI-FUNGSI API
    // ==========================================

    private static String getUtToken() {
        String targetUrl = "https://elearning.ut.ac.id/login/token.php?username=" + NIM + "&password=" + PASS + "&service=moodle_mobile_app";
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(targetUrl)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject jsonObj = new JSONObject(response.body());

            if (jsonObj.has("token")) {
                return jsonObj.getString("token");
            }
        } catch (Exception e) {
            System.out.println("❌ Gagal login API: " + e.getMessage());
        }
        return null;
    }

    // RADAR BARU KHUSUS DETEKSI TUGAS & DISKUSI
    private static void cekTugasPending(String token) {
        String urlCalendar = "https://elearning.ut.ac.id/webservice/rest/server.php?wstoken=" + token + "&wsfunction=core_calendar_get_action_events_by_timesort&moodlewsrestformat=json";

        try {
            System.out.println("📡 Menyalakan radar tugas Moodle...");
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(urlCalendar)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JSONObject jsonResponse = new JSONObject(response.body());
            JSONArray eventsArray = jsonResponse.getJSONArray("events");

            if (eventsArray.isEmpty()) {
                System.out.println("📭 Radar Kosong. Tugas dan Diskusi belum masuk ke kalender API (atau masih digembok total dari pusat).");
            } else {
                System.out.println("🚨 BINGO! Ditemukan " + eventsArray.length() + " Tugas/Diskusi di kalender!");

                // BONGKAR ISI TUGASNYA
                for (int i = 0; i < eventsArray.length(); i++) {
                    JSONObject event = eventsArray.getJSONObject(i);
                    String namaTugas = event.getString("name");
                    String namaMatkul = event.getJSONObject("course").getString("fullname");

                    System.out.println("👉 TUGAS: " + namaTugas + " | MATKUL: " + namaMatkul);

                    // Nanti kodingan kirim ke Notion bakal kita nyalain di sini
                }
            }
        } catch (Exception e) {
            System.out.println("❌ Gagal narik data tugas: " + e.getMessage());
        }
    }
}