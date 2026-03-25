package com.autotracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.cdimascio.dotenv.Dotenv; // Import brankas rahasia

public class App {
    private static final Dotenv dotenv = Dotenv.load();
    private static final String NIM = dotenv.get("UT_NIM");
    private static final String PASS = dotenv.get("UT_PASS");

    public static void sendTelegramNotification(String message) {
        try {
            String botToken = dotenv.get("TELEGRAM_BOT_TOKEN");
            String chatId = dotenv.get("TELEGRAM_CHAT_ID");

            if (botToken == null || chatId == null) {
                System.out.println("⚠️ Token atau Chat ID Telegram belum diset!");
                return;
            }

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

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("✅ Notif Telegram sukses terkirim!");
            } else {
                System.out.println("❌ Gagal kirim Telegram: " + response.body());
            }
        } catch (Exception e) {
            System.out.println("🚨 Error Telegram: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        sendTelegramNotification("Halo bro! Bot Tracker UT udah berhasil nyala! 🚀");

        System.out.println("\n⏳ [" + java.time.LocalTime.now() + "] Bot bangun! Mengecek e-learning...");

        // 1. Dapatkan Token Login
        String token = getUtToken();

        if (token != null) {
            // 2. Dapatkan User ID
            int userId = getUserId(token);

            if (userId != -1) {
                // 3. Cek Daftar Matkul
                cekDaftarMatkul(token, userId);
            }
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

    private static int getUserId(String token) {
        String urlSiteInfo = "https://elearning.ut.ac.id/webservice/rest/server.php?wstoken=" + token + "&wsfunction=core_webservice_get_site_info&moodlewsrestformat=json";
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(urlSiteInfo)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject profilJson = new JSONObject(response.body());

            if(profilJson.has("userid")) {
                return profilJson.getInt("userid");
            }
        } catch (Exception e) {
            System.out.println("❌ Gagal ambil profil: " + e.getMessage());
        }
        return -1;
    }

    private static void cekDaftarMatkul(String token, int userId) {
        String urlCourses = "https://elearning.ut.ac.id/webservice/rest/server.php?wstoken=" + token + "&wsfunction=core_enrol_get_users_courses&moodlewsrestformat=json&userid=" + userId;
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(urlCourses)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JSONArray matkulArray = new JSONArray(response.body());

            if (matkulArray.isEmpty()) {
                System.out.println("📭 Mata Kuliah masih kosong (Libur Semester). Standby...");
                sendTelegramNotification("⚠️ Tidak ditemukan pembaharuan pada web. Mata Kuliah masih kosong!");
            } else {
                System.out.println("📋 Mata Kuliah aktif terdeteksi! (Ada " + matkulArray.length() + " kelas)");
                sendTelegramNotification("🚨 Pembaharuan E-learning ditemukan " + matkulArray.length() + " Mata Kuliah yang aktif di e-learning UT.");

                for (int i = 0; i < matkulArray.length(); i++) {
                    JSONObject matkul = matkulArray.getJSONObject(i);
                    String namaMatkul = matkul.getString("fullname");
                    System.out.println("Mengirim ke Notion: " +namaMatkul);

                    //kirimNotion(namaMatkul);
                }
            }
        } catch (Exception e) {
            System.out.println("❌ Gagal narik Mata Kuliah: " + e.getMessage());
        }
    }

    public static void kirimNotion(String teksLaporan) {
        try {
            // BENERIN CARA MANGGIL BRANKAS BIAR SAMA KAYAK TELEGRAM
            String notionToken = dotenv.get("NOTION_TOKEN");
            String databaseId = dotenv.get("NOTION_DATABASE_ID"); // Nambahin _ID

            if  (notionToken == null || databaseId == null) {
                System.out.println("❌ Token atau Database NOTION belum dipasang!");
                return; // TAMBAHIN INI BIAR NGGAK ERROR PAS KOSONG
            }

            String jsonData = "{"
                    + "\"parent\": { \"database_id\": \"" + databaseId + "\" },"
                    + "\"properties\": {"
                    + "  \"Name\": {"
                    + "    \"title\": ["
                    + "      {"
                    + "        \"text\": {"
                    + "          \"content\": \"" + teksLaporan + "\""
                    + "        }"
                    + "      }"
                    + "    ]"
                    + "  }"
                    + "}"
                    + "}";

            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.notion.com/v1/pages"))
                    .header("Authorization", "Bearer " + notionToken)
                    .header("Content-Type", "application/json")
                    .header("Notion-Version", "2022-06-28")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonData))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("✅ Berhasil nulis ke Notion!");
            } else {
                System.out.println("❌ Gagal nulis ke Notion. Kode Error: " + response.statusCode());
                System.out.println("Detail: " + response.body());
            }
        } catch (Exception e) {
            System.out.println("❌ Sistem error saat mengirim ke Notion: " + e.getMessage());
        }
    }
}