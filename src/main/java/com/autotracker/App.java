package com.autotracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
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

    // ==========================================
    // MAIN
    // ==========================================

    public static void main(String[] args) {
        System.out.println("\n⏳ [" + java.time.LocalTime.now() + "] Bot bangun! Mengecek e-learning...");

        String token = getUtToken();
        if (token == null) {
            System.out.println("❌ Gagal login, bot berhenti.");
            return;
        }

        int userId = getUserId(token);
        if (userId == -1) {
            System.out.println("❌ Gagal ambil profil, bot berhenti.");
            return;
        }

        // Ambil daftar matkul — ini jadi pusat data untuk semua pengecekan
        JSONArray daftarMatkul = getDaftarMatkul(token, userId);
        if (daftarMatkul == null || daftarMatkul.length() == 0) {
            System.out.println("📭 Belum ada mata kuliah aktif. Bot tidur.");
        } else {
            // Buat peta courseId -> namaMatkul untuk lookup cepat
            Map<Integer, String> courseMap = buildCourseMap(daftarMatkul);

            // === PENGECEKAN 1: Matkul Baru (awal semester) ===
            cekDanSimpanMatkulBaru(daftarMatkul);

            // === PENGECEKAN 2: Tugas & Kuis (via calendar per course) ===
            cekTugasBaru(token, daftarMatkul, courseMap);

            // === PENGECEKAN 3: Diskusi Forum (via mod_forum API) ===
            cekDiskusiBaru(token, daftarMatkul, courseMap);
        }

        System.out.println("💤 Pengecekan selesai. Bot tidur lagi...");
    }

    // ==========================================
    // HELPER: Build course ID -> name map
    // ==========================================

    private static Map<Integer, String> buildCourseMap(JSONArray daftarMatkul) {
        Map<Integer, String> map = new HashMap<>();
        for (int i = 0; i < daftarMatkul.length(); i++) {
            JSONObject matkul = daftarMatkul.getJSONObject(i);
            map.put(matkul.getInt("id"), matkul.getString("fullname"));
        }
        return map;
    }

    // ==========================================
    // FUNGSI API MOODLE
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

    /** Ambil semua matkul yang diikuti user, kembalikan sebagai JSONArray mentah */
    private static JSONArray getDaftarMatkul(String token, int userId) {
        String urlCourses = BASE_URL + "/webservice/rest/server.php?wstoken=" + token
                + "&wsfunction=core_enrol_get_users_courses&moodlewsrestformat=json&userid=" + userId;
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(urlCourses)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new JSONArray(response.body());
        } catch (Exception e) {
            System.out.println("❌ Gagal narik Mata Kuliah: " + e.getMessage());
            return null;
        }
    }

    // ==========================================
    // PENGECEKAN 1: Matkul Baru
    // ==========================================

    private static void cekDanSimpanMatkulBaru(JSONArray daftarMatkul) {
        System.out.println("\n📋 [CEK 1] Ada " + daftarMatkul.length() + " Matkul. Mengecek matkul baru...");
        int matkulBaru = 0;

        for (int i = 0; i < daftarMatkul.length(); i++) {
            JSONObject matkul = daftarMatkul.getJSONObject(i);
            String namaMatkul = matkul.getString("fullname");
            String penanda = "[MATKUL] " + namaMatkul;

            if (!sudahAdaDiNotion(penanda)) {
                matkulBaru++;
                System.out.println("🆕 Matkul BARU: " + namaMatkul);
                kirimNotionRapi(penanda, namaMatkul);
            } else {
                System.out.println("⏭️  Skip matkul: " + namaMatkul);
            }
        }

        if (matkulBaru > 0) {
            sendTelegramNotification("📚 Semester Baru! Ada " + matkulBaru + " mata kuliah baru aktif di e-learning UT!");
        }
    }

    // ==========================================
    // PENGECEKAN 2: Tugas & Kuis (Calendar per Course)
    // ==========================================

    private static void cekTugasBaru(String token, JSONArray daftarMatkul, Map<Integer, String> courseMap) {
        System.out.println("\n📡 [CEK 2] Mengecek tugas & kuis via calendar...");

        try {
            // Gunakan API per-course agar lebih akurat, dengan timesortfrom = sekarang
            long sekarang = java.time.Instant.now().getEpochSecond();
            StringBuilder url = new StringBuilder(BASE_URL + "/webservice/rest/server.php?wstoken=" + token);
            url.append("&wsfunction=core_calendar_get_action_events_by_courses");
            url.append("&moodlewsrestformat=json");
            url.append("&timesortfrom=").append(sekarang);

            for (int i = 0; i < daftarMatkul.length(); i++) {
                url.append("&courseids[").append(i).append("]=")
                   .append(daftarMatkul.getJSONObject(i).getInt("id"));
            }

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url.toString())).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            JSONObject jsonResponse = new JSONObject(response.body());
            JSONArray eventsArray = jsonResponse.getJSONArray("events");
            int tugasBaru = 0;

            if (eventsArray.isEmpty()) {
                System.out.println("📭 Belum ada tugas/kuis mendatang.");
            } else {
                System.out.println("🔍 Ditemukan " + eventsArray.length() + " event kalender. Mengecek ke Notion...");

                for (int i = 0; i < eventsArray.length(); i++) {
                    JSONObject event = eventsArray.getJSONObject(i);
                    String namaTugas = event.getString("name");
                    int courseId = event.getJSONObject("course").getInt("id");
                    String namaMatkul = courseMap.getOrDefault(courseId, "Matkul Tidak Diketahui");

                    if (sudahAdaDiNotion(namaTugas)) {
                        System.out.println("⏭️  Skip tugas: " + namaTugas);
                        continue;
                    }

                    tugasBaru++;
                    System.out.println("👉 Tugas BARU: " + namaTugas + " | " + namaMatkul);
                    kirimNotionRapi(namaTugas, namaMatkul);
                }

                if (tugasBaru > 0) {
                    sendTelegramNotification("🚨 Alert! Ada " + tugasBaru + " Tugas/Kuis BARU di e-learning! Cek Notion kamu.");
                } else {
                    System.out.println("💤 Semua tugas sudah tercatat, tidak ada yang baru.");
                }
            }
        } catch (Exception e) {
            System.out.println("❌ Gagal narik data tugas: " + e.getMessage());
        }
    }

    // ==========================================
    // PENGECEKAN 3: Diskusi Forum
    // ==========================================

    private static void cekDiskusiBaru(String token, JSONArray daftarMatkul, Map<Integer, String> courseMap) {
        System.out.println("\n💬 [CEK 3] Mengecek diskusi forum...");

        try {
            // Step 1: Ambil semua forum dari semua matkul
            StringBuilder forumsUrl = new StringBuilder(BASE_URL + "/webservice/rest/server.php?wstoken=" + token);
            forumsUrl.append("&wsfunction=mod_forum_get_forums_by_courses");
            forumsUrl.append("&moodlewsrestformat=json");

            for (int i = 0; i < daftarMatkul.length(); i++) {
                forumsUrl.append("&courseids[").append(i).append("]=")
                         .append(daftarMatkul.getJSONObject(i).getInt("id"));
            }

            HttpRequest forumsReq = HttpRequest.newBuilder().uri(URI.create(forumsUrl.toString())).GET().build();
            HttpResponse<String> forumsResp = httpClient.send(forumsReq, HttpResponse.BodyHandlers.ofString());
            JSONArray daftarForum = new JSONArray(forumsResp.body());

            if (daftarForum.isEmpty()) {
                System.out.println("📭 Tidak ada forum ditemukan.");
                return;
            }

            System.out.println("📂 Ditemukan " + daftarForum.length() + " forum. Mengecek diskusi...");
            int diskusiBaru = 0;

            // Step 2: Per forum, ambil diskusinya
            for (int f = 0; f < daftarForum.length(); f++) {
                JSONObject forum = daftarForum.getJSONObject(f);
                int forumId = forum.getInt("id");
                int courseId = forum.getInt("course");
                String namaMatkul = courseMap.getOrDefault(courseId, "Matkul Tidak Diketahui");

                String discussionsUrl = BASE_URL + "/webservice/rest/server.php?wstoken=" + token
                        + "&wsfunction=mod_forum_get_forum_discussions&moodlewsrestformat=json&forumid=" + forumId;

                HttpRequest discReq = HttpRequest.newBuilder().uri(URI.create(discussionsUrl)).GET().build();
                HttpResponse<String> discResp = httpClient.send(discReq, HttpResponse.BodyHandlers.ofString());

                JSONObject discResponse = new JSONObject(discResp.body());

                // API mengembalikan object dengan key "discussions"
                if (!discResponse.has("discussions")) continue;
                JSONArray discussions = discResponse.getJSONArray("discussions");

                for (int d = 0; d < discussions.length(); d++) {
                    JSONObject discussion = discussions.getJSONObject(d);
                    String namaDiskusi = discussion.getString("name");

                    // Filter: hanya proses forum yang relevan (Diskusi, Kehadiran, Tugas)
                    if (!isForumRelevan(namaDiskusi)) {
                        System.out.println("🚫 Dilewati (bukan forum tuton): " + namaDiskusi);
                        continue;
                    }

                    // Pakai prefix agar tidak bentrok dengan nama tugas di Notion
                    String penanda = "[DISKUSI] " + namaDiskusi;

                    // Cek dengan Name + Mata Kuliah agar Diskusi.1 dari matkul berbeda tidak saling skip
                    if (sudahAdaDiNotion(penanda, namaMatkul)) {
                        System.out.println("⏭️  Skip diskusi: " + namaDiskusi + " | " + namaMatkul);
                        continue;
                    }

                    diskusiBaru++;
                    System.out.println("💬 Diskusi BARU: " + namaDiskusi + " | " + namaMatkul);
                    kirimNotionRapi(penanda, namaMatkul);
                }
            }

            if (diskusiBaru > 0) {
                sendTelegramNotification("💬 Ada " + diskusiBaru + " topik Diskusi BARU di forum! Cek Notion kamu.");
            } else {
                System.out.println("💤 Tidak ada diskusi baru.");
            }

        } catch (Exception e) {
            System.out.println("❌ Gagal narik diskusi forum: " + e.getMessage());
        }
    }

    // ==========================================
    // FUNGSI NOTION & TELEGRAM
    // ==========================================

    /**
     * Filter: hanya ambil forum yang relevan untuk perkuliahan UT.
     * Hanya Diskusi.X, Kehadiran, dan Tugas yang perlu ditrack.
     */
    private static boolean isForumRelevan(String namaForum) {
        String lower = namaForum.toLowerCase();
        return lower.startsWith("diskusi") ||
               lower.startsWith("kehadiran") ||
               lower.startsWith("tugas");
    }

    /**
     * Cek ke Notion apakah entri dengan nama ini sudah pernah dicatat.
     * Persistent state — aman dipakai di GitHub Actions.
     */
    private static boolean sudahAdaDiNotion(String nama) {
        try {
            String notionToken = dotenv.get("NOTION_TOKEN");
            String databaseId = dotenv.get("NOTION_DATABASE_ID");
            if (notionToken == null || databaseId == null) return false;

            JSONObject filter = new JSONObject();
            JSONObject condition = new JSONObject();
            JSONObject titleFilter = new JSONObject();
            titleFilter.put("equals", nama);
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
            return hasil.getJSONArray("results").length() > 0;

        } catch (Exception e) {
            System.out.println("⚠️ Gagal query Notion: " + e.getMessage());
            return false; // Anggap belum ada agar tidak terlewat
        }
    }

    /**
     * Overload: cek berdasarkan Name DAN Mata Kuliah sekaligus.
     * Dipakai untuk diskusi agar "Diskusi.1" dari matkul berbeda tidak saling skip.
     */
    private static boolean sudahAdaDiNotion(String nama, String namaMatkul) {
        try {
            String notionToken = dotenv.get("NOTION_TOKEN");
            String databaseId = dotenv.get("NOTION_DATABASE_ID");
            if (notionToken == null || databaseId == null) return false;

            JSONObject nameCondition = new JSONObject();
            JSONObject nameTitleFilter = new JSONObject();
            nameTitleFilter.put("equals", nama);
            nameCondition.put("property", "Name");
            nameCondition.put("title", nameTitleFilter);

            JSONObject matkulCondition = new JSONObject();
            JSONObject matkulSelectFilter = new JSONObject();
            matkulSelectFilter.put("equals", namaMatkul);
            matkulCondition.put("property", "Mata Kuliah");
            matkulCondition.put("select", matkulSelectFilter);

            JSONArray andArray = new JSONArray();
            andArray.put(nameCondition);
            andArray.put(matkulCondition);

            JSONObject andFilter = new JSONObject();
            andFilter.put("and", andArray);

            JSONObject body = new JSONObject();
            body.put("filter", andFilter);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.notion.com/v1/databases/" + databaseId + "/query"))
                    .header("Authorization", "Bearer " + notionToken)
                    .header("Content-Type", "application/json")
                    .header("Notion-Version", "2022-06-28")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject hasil = new JSONObject(response.body());
            return hasil.getJSONArray("results").length() > 0;

        } catch (Exception e) {
            System.out.println("⚠️ Gagal query Notion (2-param): " + e.getMessage());
            return false;
        }
    }

    public static void kirimNotionRapi(String namaEntri, String namaMatkul) {
        try {
            String notionToken = dotenv.get("NOTION_TOKEN");
            String databaseId = dotenv.get("NOTION_DATABASE_ID");
            if (notionToken == null || databaseId == null) return;

            JSONObject jsonBody = new JSONObject();
            JSONObject parent = new JSONObject();
            parent.put("database_id", databaseId);
            jsonBody.put("parent", parent);

            JSONObject properties = new JSONObject();

            JSONObject nameProp = new JSONObject();
            JSONArray titleArray = new JSONArray();
            JSONObject titleContent = new JSONObject();
            JSONObject textObj = new JSONObject();
            textObj.put("content", namaEntri);
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
                System.out.println("✅ Sukses nulis ke Notion: " + namaEntri);
            } else {
                System.out.println("❌ Gagal nulis Notion: " + response.body());
            }
        } catch (Exception e) {
            System.out.println("❌ Sistem error Notion: " + e.getMessage());
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
}