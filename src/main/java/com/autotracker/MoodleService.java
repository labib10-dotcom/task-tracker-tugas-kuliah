package com.autotracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import io.github.cdimascio.dotenv.Dotenv;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Semua interaksi dengan API e-learning Moodle Universitas Terbuka.
 */
public class MoodleService {

    private static final Dotenv dotenv = Dotenv.load();
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final String BASE_URL = "https://elearning.ut.ac.id";
    private static final String NIM = dotenv.get("UT_NIM");
    private static final String PASS = dotenv.get("UT_PASS");

    /** Login ke Moodle dan dapatkan token API */
    public static String getToken() {
        String url = BASE_URL + "/login/token.php?username=" + NIM + "&password=" + PASS + "&service=moodle_mobile_app";
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JSONObject json = new JSONObject(res.body());
            if (json.has("token")) return json.getString("token");
        } catch (Exception e) {
            System.out.println("❌ Gagal login API: " + e.getMessage());
        }
        return null;
    }

    /** Ambil user ID dari profil Moodle */
    public static int getUserId(String token) {
        String url = BASE_URL + "/webservice/rest/server.php?wstoken=" + token
                + "&wsfunction=core_webservice_get_site_info&moodlewsrestformat=json";
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JSONObject json = new JSONObject(res.body());
            if (json.has("userid")) return json.getInt("userid");
        } catch (Exception e) {
            System.out.println("❌ Gagal ambil profil: " + e.getMessage());
        }
        return -1;
    }

    /** Ambil daftar mata kuliah yang diikuti user */
    public static JSONArray getDaftarMatkul(String token, int userId) {
        String url = BASE_URL + "/webservice/rest/server.php?wstoken=" + token
                + "&wsfunction=core_enrol_get_users_courses&moodlewsrestformat=json&userid=" + userId;
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return new JSONArray(res.body());
        } catch (Exception e) {
            System.out.println("❌ Gagal narik Mata Kuliah: " + e.getMessage());
            return null;
        }
    }

    /** Buat peta courseId -> namaMatkul untuk lookup cepat */
    public static Map<Integer, String> buildCourseMap(JSONArray daftarMatkul) {
        Map<Integer, String> map = new HashMap<>();
        for (int i = 0; i < daftarMatkul.length(); i++) {
            JSONObject m = daftarMatkul.getJSONObject(i);
            map.put(m.getInt("id"), m.getString("fullname"));
        }
        return map;
    }

    /** Ambil event kalender (tugas, kuis) dari semua course mulai sekarang */
    public static JSONArray getCalendarEvents(String token, JSONArray daftarMatkul) throws Exception {
        long sekarang = java.time.Instant.now().getEpochSecond();
        StringBuilder url = new StringBuilder(BASE_URL + "/webservice/rest/server.php?wstoken=" + token);
        url.append("&wsfunction=core_calendar_get_action_events_by_courses");
        url.append("&moodlewsrestformat=json");
        url.append("&timesortfrom=").append(sekarang);

        for (int i = 0; i < daftarMatkul.length(); i++) {
            url.append("&courseids[").append(i).append("]=")
               .append(daftarMatkul.getJSONObject(i).getInt("id"));
        }

        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url.toString())).GET().build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return new JSONObject(res.body()).getJSONArray("events");
    }

    /** Ambil semua forum dari daftar course */
    public static JSONArray getForumsByCourses(String token, JSONArray daftarMatkul) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL + "/webservice/rest/server.php?wstoken=" + token);
        url.append("&wsfunction=mod_forum_get_forums_by_courses");
        url.append("&moodlewsrestformat=json");

        for (int i = 0; i < daftarMatkul.length(); i++) {
            url.append("&courseids[").append(i).append("]=")
               .append(daftarMatkul.getJSONObject(i).getInt("id"));
        }

        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url.toString())).GET().build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return new JSONArray(res.body());
    }

    /** Ambil semua diskusi dari satu forum */
    public static JSONArray getForumDiscussions(String token, int forumId) throws Exception {
        String url = BASE_URL + "/webservice/rest/server.php?wstoken=" + token
                + "&wsfunction=mod_forum_get_forum_discussions&moodlewsrestformat=json&forumid=" + forumId;
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        JSONObject json = new JSONObject(res.body());
        return json.has("discussions") ? json.getJSONArray("discussions") : new JSONArray();
    }

    /**
     * Filter relevansi forum UT.
     * Hanya Diskusi.X, Kehadiran Sesi, dan Tugas yang perlu ditrack.
     */
    public static boolean isForumRelevan(String namaForum) {
        String lower = namaForum.toLowerCase();
        return lower.startsWith("diskusi") ||
               lower.startsWith("kehadiran") ||
               lower.startsWith("tugas");
    }

    /**
     * Cek apakah user sudah pernah membalas/berpartisipasi di suatu diskusi.
     * Dipakai sebagai FALLBACK jika cmid forum tidak tersedia.
     */
    public static boolean sudahBerpartisipasi(String token, int discussionId, int userId) {
        String url = BASE_URL + "/webservice/rest/server.php?wstoken=" + token
                + "&wsfunction=mod_forum_get_discussion_posts"
                + "&moodlewsrestformat=json"
                + "&discussionid=" + discussionId;
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JSONObject json = new JSONObject(res.body());
            if (!json.has("posts")) return false;

            JSONArray posts = json.getJSONArray("posts");
            for (int i = 0; i < posts.length(); i++) {
                if (posts.getJSONObject(i).getInt("userid") == userId) {
                    return true;
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ Gagal cek partisipasi diskusi " + discussionId + ": " + e.getMessage());
        }
        return false;
    }

    /**
     * Ambil completion status semua aktivitas di satu course.
     * Ini adalah sumber data yang SAMA dengan tanda hijau "✓ Done" di UI Moodle.
     *
     * Return: Map dari cmid -> state (0=belum selesai, 1=selesai, 2=pass, 3=fail)
     * cmid adalah ID course module — tiap forum punya cmid sendiri.
     */
    public static Map<Integer, Integer> getCompletionStatus(String token, int courseId, int userId) {
        Map<Integer, Integer> completionMap = new HashMap<>();
        String url = BASE_URL + "/webservice/rest/server.php?wstoken=" + token
                + "&wsfunction=core_completion_get_activities_completion_status"
                + "&moodlewsrestformat=json"
                + "&courseid=" + courseId
                + "&userid=" + userId;
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JSONObject json = new JSONObject(res.body());
            if (!json.has("statuses")) return completionMap;

            JSONArray statuses = json.getJSONArray("statuses");
            for (int i = 0; i < statuses.length(); i++) {
                JSONObject s = statuses.getJSONObject(i);
                completionMap.put(s.getInt("cmid"), s.getInt("state"));
            }
        } catch (Exception e) {
            System.out.println("⚠️ Gagal ambil completion status course " + courseId + ": " + e.getMessage());
        }
        return completionMap;
    }
}
