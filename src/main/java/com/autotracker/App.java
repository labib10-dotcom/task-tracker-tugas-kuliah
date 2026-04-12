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
    
    // Optimasi: HttpClient digunakan ulang (thread-safe)
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final String BASE_URL = "https://elearning.ut.ac.id";

    public static void sendTelegramNotification(String message) {
        try {
            String botToken = dotenv.get("TELEGRAM_BOT_TOKEN");
            String chatId = dotenv.get("TELEGRAM_CHAT_ID");

            if (botToken == null || chatId == null) return;

            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("chat_id", chatId);
            jsonBody.put("text", message);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody.toString()))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.out.println("🚨 Error Telegram: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
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
        String targetUrl = BASE_URL + "/login/token.php?username=" + NIM + "&password=" + PASS + "&service=moodle_mobile_app";
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(targetUrl)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject jsonObj = new JSONObject(response.body());

            if (jsonObj.has("token")) return jsonObj.getString("token");
        } catch (Exception e) {
            System.out.println("❌ Gagal login API: " + e.getMessage());
        }
        return null;
    }

    private static int getUserId(String token) {
        String urlSiteInfo = BASE_URL + "/webservice/rest/server.php?wstoken=" + token + "&wsfunction=core_webservice_get_site_info&moodlewsrestformat=json";
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(urlSiteInfo)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject profilJson = new JSONObject(response.body());
            if (profilJson.has("userid")) return profilJson.getInt("userid");
        } catch (Exception e) {
            System.out.println("❌ Gagal ambil profil: " + e.getMessage());
        }
        return -1;
    }

    private static int cekDaftarMatkul(String token, int userId) {
        String urlCourses = BASE_URL + "/webservice/rest/server.php?wstoken=" + token + "&wsfunction=core_enrol_get_users_courses&moodlewsrestformat=json&userid=" + userId;
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(urlCourses)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JSONArray matkulArray = new JSONArray(response.body());

            int jumlah = matkulArray.length();
            int matkulBaru = 0;

            if (jumlah > 0) {
                System.out.println("📋 Radar 1: Ada " + jumlah + " Matkul. Mengecek matkul baru ke Notion...");

                for (int i = 0; i < matkulArray.length(); i++) {
                    JSONObject matkul = matkulArray.getJSONObject(i);
                    String namaMatkul = matkul.getString("fullname");
                    // Pakai prefix khusus agar tidak bentrok dengan nama tugas di Notion
                    String penanda = "[MATKUL] " + namaMatkul;

                    if (!sudahAdaDiNotion(penanda)) {
                        matkulBaru++;
                        System.out.println("🆕 Matkul BARU terdeteksi: " + namaMatkul);
                        // Simpan penanda ke Notion agar run berikutnya tidak kirim lagi
                        kirimNotionRapi(penanda, namaMatkul);
                    } else {
                        System.out.println("⏭️ Skip matkul (sudah tercatat): " + namaMatkul);
                    }
                }

                if (matkulBaru > 0) {
                    sendTelegramNotification("📚 Semester Baru! Ada " + matkulBaru + " mata kuliah baru aktif di e-learning UT! Cek Notion kamu.");
                }
            }
            return jumlah;
        } catch (Exception e) {
            System.out.println("❌ Gagal narik Mata Kuliah: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Cek ke Notion apakah tugas dengan nama ini sudah pernah dicatat.
     * Ini adalah state management yang persistent — aman dipakai di GitHub Actions.
     */
    private static boolean sudahAdaDiNotion(String namaTugas) {
        try {
            String notionToken = dotenv.get("NOTION_TOKEN");
            String databaseId = dotenv.get("NOTION_DATABASE_ID");
            if (notionToken == null || databaseId == null) return false;

            // Query Notion: cari page dengan title == namaTugas
            JSONObject filter = new JSONObject();
            JSONObject condition = new JSONObject();
            JSONObject titleFilter = new JSONObject();
            titleFilter.put("equals", namaTugas);
            condition.put("property", "Name");
            condition.put("title", titleFilter);
            filter.put("filter", condition);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.notion.com/v1/databases/" + databaseId + "/query"))
                    .header("Authorization", "Bearer " + notionToken)
                    .header("Content-Type", "application/json")
                    .header("Notion-Version", "2022-06-28")
                    .POST(HttpRequest.BodyPublishers.ofString(filter.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject hasil = new JSONObject(response.body());

            // Kalau results-nya tidak kosong, berarti tugas ini sudah ada
            return hasil.getJSONArray("results").length() > 0;

        } catch (Exception e) {
            System.out.println("⚠️ Gagal query Notion: " + e.getMessage());
            // Kalau gagal query, anggap belum ada agar tidak ada yang terlewat
            return false;
        }
    }

    private static void cekTugasPending(String token, int jumlahMatkul) {
        String urlCalendar = BASE_URL + "/webservice/rest/server.php?wstoken=" + token + "&wsfunction=core_calendar_get_action_events_by_timesort&moodlewsrestformat=json";

        try {
            System.out.println("📡 Menyalakan radar tugas Moodle...");
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(urlCalendar)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            JSONObject jsonResponse = new JSONObject(response.body());
            JSONArray eventsArray = jsonResponse.getJSONArray("events");
            int newTasksCount = 0;

            if (eventsArray.isEmpty()) {
                System.out.println("📭 Radar Kosong. Belum ada tugas/diskusi baru.");
            } else {
                System.out.println("🔍 Ditemukan " + eventsArray.length() + " event. Mengecek duplikasi ke Notion...");

                for (int i = 0; i < eventsArray.length(); i++) {
                    JSONObject event = eventsArray.getJSONObject(i);
                    String namaTugas = event.getString("name");
                    String namaMatkul = event.getJSONObject("course").getString("fullname");

                    if (sudahAdaDiNotion(namaTugas)) {
                        System.out.println("⏭️ Skip (sudah ada di Notion): " + namaTugas);
                        continue;
                    }

                    // Tugas baru! Catat dan kirim notif
                    newTasksCount++;
                    System.out.println("👉 Tugas BARU! Memproses: " + namaTugas + " | " + namaMatkul);
                    kirimNotionRapi(namaTugas, namaMatkul);
                }

                if (newTasksCount > 0) {
                    sendTelegramNotification("🚨 Alert! Ada " + newTasksCount + " Tugas/Diskusi BARU di e-learning! Cek Notion kamu.");
                } else {
                    System.out.println("💤 Semua tugas sudah tercatat di Notion sebelumnya. Tidak ada yang baru.");
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

            // AMAN DARI ERROR: Gunakan JSONObject agar otomatis sanitize karakter aneh
            JSONObject jsonBody = new JSONObject();
            JSONObject parent = new JSONObject();
            parent.put("database_id", databaseId);
            jsonBody.put("parent", parent);

            JSONObject properties = new JSONObject();

            JSONObject nameProp = new JSONObject();
            JSONArray titleArray = new JSONArray();
            JSONObject titleContent = new JSONObject();
            JSONObject textObj = new JSONObject();
            textObj.put("content", namaTugas);
            titleContent.put("text", textObj);
            titleArray.put(titleContent);
            nameProp.put("title", titleArray);

            JSONObject matkulProp = new JSONObject();
            JSONObject selectObj = new JSONObject();
            selectObj.put("name", namaMatkul);
            matkulProp.put("select", selectObj);

            properties.put("Name", nameProp);
            properties.put("Mata Kuliah", matkulProp);

            jsonBody.put("properties", properties);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.notion.com/v1/pages"))
                    .header("Authorization", "Bearer " + notionToken)
                    .header("Content-Type", "application/json")
                    .header("Notion-Version", "2022-06-28")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

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