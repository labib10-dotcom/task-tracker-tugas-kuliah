package com.autotracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
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
    private static final Path HISTORY_FILE = Paths.get("sent_tasks.txt");

    private static Set<String> loadSentTasks() {
        try {
            if (Files.exists(HISTORY_FILE)) {
                return new HashSet<>(Files.readAllLines(HISTORY_FILE));
            }
        } catch (Exception e) {
            System.out.println("⚠️ Gagal membaca history tugas: " + e.getMessage());
        }
        return new HashSet<>();
    }

    private static void saveTaskToHistory(String taskId) {
        try {
            Files.write(HISTORY_FILE, (taskId + System.lineSeparator()).getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.out.println("⚠️ Gagal menyimpan history: " + e.getMessage());
        }
    }

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
        String urlCalendar = BASE_URL + "/webservice/rest/server.php?wstoken=" + token + "&wsfunction=core_calendar_get_action_events_by_timesort&moodlewsrestformat=json";

        try {
            System.out.println("📡 Menyalakan radar tugas Moodle...");
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(urlCalendar)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            JSONObject jsonResponse = new JSONObject(response.body());
            JSONArray eventsArray = jsonResponse.getJSONArray("events");
            
            Set<String> sentTasks = loadSentTasks();
            int newTasksCount = 0;

            if (eventsArray.isEmpty()) {
                System.out.println("📭 Radar Kosong. Belum ada tugas/diskusi baru.");
            } else {
                for (int i = 0; i < eventsArray.length(); i++) {
                    JSONObject event = eventsArray.getJSONObject(i);
                    String taskId = String.valueOf(event.getInt("id"));
                    
                    if (!sentTasks.contains(taskId)) {
                        newTasksCount++;
                    }
                }

                if (newTasksCount > 0) {
                    System.out.println("🚨 BINGO! Ditemukan " + newTasksCount + " Tugas Baru!");
                    sendTelegramNotification("🚨 Alert! Ada " + newTasksCount + " Tugas/Diskusi BARU di e-learning!");

                    // BONGKAR TUGAS DAN KIRIM RAPI KE NOTION
                    for (int i = 0; i < eventsArray.length(); i++) {
                        JSONObject event = eventsArray.getJSONObject(i);
                        String taskId = String.valueOf(event.getInt("id"));

                        if (!sentTasks.contains(taskId)) {
                            String namaTugas = event.getString("name");
                            String namaMatkul = event.getJSONObject("course").getString("fullname");

                            System.out.println("👉 Memproses: " + namaTugas + " | " + namaMatkul);

                            kirimNotionRapi(namaTugas, namaMatkul);
                            saveTaskToHistory(taskId);
                        }
                    }
                } else {
                    System.out.println("💤 Memang ada " + eventsArray.length() + " Tugas pending, tapi semuanya sudah pernah diteruskan. Tidak perlu diulangi.");
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