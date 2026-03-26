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

    public static void sendTelegramNotification(String message) {
        try {
            String botToken = dotenv.get("TELEGRAM_BOT_TOKEN");
            String chatId = dotenv.get("TELEGRAM_CHAT_ID");

            if (botToken == null || chatId == null) return;

            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("chat_id", chatId);
            jsonBody.put("text", message);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody.toString()))
                    .build();

            client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.out.println("🚨 Error Telegram: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        sendTelegramNotification("Halo bro! Bot Tracker UT udah berhasil nyala! 🚀");
        System.out.println("\n⏳ [" + java.time.LocalTime.now() + "] Bot bangun! Mengecek e-learning...");

        String token = getUtToken();
        if (token != null) {
            int userId = getUserId(token);
            int jumlahMatkul = 0;


            if (userId != -1) {
                jumlahMatkul = cekDaftarMatkul(token, userId);
            }

            cekTugasPending(token, jumlahMatkul);
        }

        System.out.println("💤 Pengecekan selesai. Bot tidur lagi...");
    }

    // ==========================================
    // FUNGSI API MOODLE & NOTION
    // ==========================================

    private static String getUtToken() {
        String targetUrl = "https://elearning.ut.ac.id/login/token.php?username=" + NIM + "&password=" + PASS + "&service=moodle_mobile_app";
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(targetUrl)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject jsonObj = new JSONObject(response.body());

            if (jsonObj.has("token")) return jsonObj.getString("token");
        } catch (Exception e) {
            System.out.println("❌ Gagal login API: " + e.getMessage());
        }
        return null;
    }

    private static int getUserId(String token) {
        String urlSiteInfo = "https://elearning.ut.ac.id/webservice/rest/server.php?wstoken=" + token + "&wsfunction=core_webservice_get_site_info&moodlewsrestformat=json";
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(urlSiteInfo)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject profilJson = new JSONObject(response.body());
            if (profilJson.has("userid")) return profilJson.getInt("userid");
        } catch (Exception e) {
            System.out.println("❌ Gagal ambil profil: " + e.getMessage());
        }
        return -1;
    }

    private static int cekDaftarMatkul(String token, int userId) {
        String urlCourses = "https://elearning.ut.ac.id/webservice/rest/server.php?wstoken=" + token + "&wsfunction=core_enrol_get_users_courses&moodlewsrestformat=json&userid=" + userId;
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(urlCourses)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JSONArray matkulArray = new JSONArray(response.body());

            int jumlah = matkulArray.length();

            if (jumlah > 0) {
                System.out.println("📋 Radar 1: Ada " + matkulArray.length() + " Matkul.");
                sendTelegramNotification("📚 Info! Mata Kuliah: ditemukan ada " + matkulArray.length() + " mata kuliah baru telah aktif di e-learning UT!");
            }
            return jumlah;
        } catch (Exception e) {
            System.out.println("❌ Gagal narik Mata Kuliah: " + e.getMessage());
            return 0;
        }
    }

    private static void cekTugasPending(String token, int jumlahMatkul) {
        String urlCalendar = "https://elearning.ut.ac.id/webservice/rest/server.php?wstoken=" + token + "&wsfunction=core_calendar_get_action_events_by_timesort&moodlewsrestformat=json";

        try {
            System.out.println("📡 Menyalakan radar tugas Moodle...");
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(urlCalendar)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JSONObject jsonResponse = new JSONObject(response.body());
            JSONArray eventsArray = jsonResponse.getJSONArray("events");

            if (eventsArray.isEmpty()) {
                System.out.println("📭 Radar Kosong. Belum ada tugas/diskusi baru.");
                if (jumlahMatkul >= 9) {
                    sendTelegramNotification("📭 Status Laporan: Tuton belum dimulai. Belum ada tugas atau diskusi baru yang masuk ke sistem.");
                } else {
                    sendTelegramNotification("📭 Status Laporan: Tuton belum dimulai. Santai ae dulu bro");
                }

            } else {
                System.out.println("🚨 BINGO! Ditemukan " + eventsArray.length() + " Tugas!");
                sendTelegramNotification("🚨 Alert! Ada " + eventsArray.length() + " Tugas/Diskusi pending di e-learning!");

                // BONGKAR TUGAS DAN KIRIM RAPI KE NOTION
                for (int i = 0; i < eventsArray.length(); i++) {
                    JSONObject event = eventsArray.getJSONObject(i);
                    String namaTugas = event.getString("name");
                    String namaMatkul = event.getJSONObject("course").getString("fullname");

                    System.out.println("👉 Memproses: " + namaTugas + " | " + namaMatkul);

                    kirimNotionRapi(namaTugas, namaMatkul);
                }
            }
        } catch (Exception e) {
            System.out.println("❌ Gagal narik data tugas: " + e.getMessage());
        }
    }

    public static void kirimNotionRapi(String namaTugas, String namaMatkul) {
        try {
            String notionToken = dotenv.get("NOTION_TOKEN");
            String databaseId = dotenv.get("NOTION_DATABASE_ID");

            if  (notionToken == null || databaseId == null) return;

            // Bersihin tanda kutip biar format JSON gak rusak
            namaTugas = namaTugas.replace("\"", "\\\"");
            namaMatkul = namaMatkul.replace("\"", "\\\"");

            // INI MAGIC-NYA: Kita masukin ke kolom Name dan Mata Kuliah
            String jsonData = "{"
                    + "\"parent\": { \"database_id\": \"" + databaseId + "\" },"
                    + "\"properties\": {"
                    + "  \"Name\": {"
                    + "    \"title\": [ { \"text\": { \"content\": \"" + namaTugas + "\" } } ]"
                    + "  },"
                    + "  \"Mata Kuliah\": {"
                    + "    \"select\": { \"name\": \"" + namaMatkul + "\" }"
                    + "  }"
                    + "}"
                    + "}";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.notion.com/v1/pages"))
                    .header("Authorization", "Bearer " + notionToken)
                    .header("Content-Type", "application/json")
                    .header("Notion-Version", "2022-06-28")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonData))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("✅ Sukses nulis ke Notion: " + namaTugas);
            } else {
                System.out.println("❌ Gagal nulis Notion: " + response.body());
            }
        } catch (Exception e) {
            System.out.println("❌ Sistem error Notion: " + e.getMessage());
        }
    }
}