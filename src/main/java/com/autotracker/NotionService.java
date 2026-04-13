package com.autotracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import io.github.cdimascio.dotenv.Dotenv;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Semua interaksi dengan Notion API.
 * Dipakai sebagai persistent state management agar bot tidak duplikasi data
 * walau dijalankan berkali-kali di GitHub Actions (yang tidak punya file lokal).
 */
public class NotionService {

    private static final Dotenv dotenv = Dotenv.load();
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final String NOTION_API = "https://api.notion.com/v1";
    private static final String NOTION_VERSION = "2022-06-28";

    private static String getToken() { return dotenv.get("NOTION_TOKEN"); }
    private static String getDbId()  { return dotenv.get("NOTION_DATABASE_ID"); }

    /**
     * Cek apakah entri dengan nama ini sudah ada di Notion (cek Name saja).
     * Dipakai untuk matkul, karena nama matkul sudah unik.
     */
    public static boolean sudahAda(String nama) {
        JSONObject titleFilter = new JSONObject();
        titleFilter.put("equals", nama);
        JSONObject condition = new JSONObject();
        condition.put("property", "Name");
        condition.put("title", titleFilter);
        JSONObject body = new JSONObject();
        body.put("filter", condition);
        return queryNotion(body) > 0;
    }

    /**
     * Cek apakah entri dengan nama + mata kuliah ini sudah ada di Notion (cek keduanya).
     * Dipakai untuk diskusi dan tugas agar "Diskusi.1" dari matkul berbeda tidak saling skip.
     */
    public static boolean sudahAda(String nama, String namaMatkul) {
        JSONObject nameCondition = buildTitleFilter("Name", nama);
        JSONObject matkulCondition = buildSelectFilter("Mata Kuliah", namaMatkul);

        JSONArray andArray = new JSONArray();
        andArray.put(nameCondition);
        andArray.put(matkulCondition);

        JSONObject body = new JSONObject();
        body.put("filter", new JSONObject().put("and", andArray));
        return queryNotion(body) > 0;
    }

    /** Simpan entri baru ke Notion database */
    public static void simpan(String namaEntri, String namaMatkul) {
        try {
            String token = getToken();
            String dbId = getDbId();
            if (token == null || dbId == null) return;

            JSONObject textObj = new JSONObject();
            textObj.put("content", namaEntri);
            JSONObject titleContent = new JSONObject();
            titleContent.put("text", textObj);
            JSONArray titleArray = new JSONArray();
            titleArray.put(titleContent);
            JSONObject nameProp = new JSONObject();
            nameProp.put("title", titleArray);

            JSONObject selectObj = new JSONObject();
            selectObj.put("name", namaMatkul);
            JSONObject matkulProp = new JSONObject();
            matkulProp.put("select", selectObj);

            JSONObject properties = new JSONObject();
            properties.put("Name", nameProp);
            properties.put("Mata Kuliah", matkulProp);

            JSONObject parent = new JSONObject();
            parent.put("database_id", dbId);

            JSONObject body = new JSONObject();
            body.put("parent", parent);
            body.put("properties", properties);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(NOTION_API + "/pages"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Notion-Version", NOTION_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                System.out.println("✅ Tersimpan di Notion: " + namaEntri);
            } else {
                System.out.println("❌ Gagal simpan Notion: " + res.body());
            }
        } catch (Exception e) {
            System.out.println("❌ Error Notion: " + e.getMessage());
        }
    }

    // ==========================================
    // HELPER PRIVATE
    // ==========================================

    private static int queryNotion(JSONObject body) {
        try {
            String token = getToken();
            String dbId = getDbId();
            if (token == null || dbId == null) return 0;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(NOTION_API + "/databases/" + dbId + "/query"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Notion-Version", NOTION_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return new JSONObject(res.body()).getJSONArray("results").length();
        } catch (Exception e) {
            System.out.println("⚠️ Gagal query Notion: " + e.getMessage());
            return 0; // Anggap belum ada agar tidak ada yang terlewat
        }
    }

    private static JSONObject buildTitleFilter(String property, String value) {
        JSONObject filter = new JSONObject();
        filter.put("equals", value);
        JSONObject condition = new JSONObject();
        condition.put("property", property);
        condition.put("title", filter);
        return condition;
    }

    private static JSONObject buildSelectFilter(String property, String value) {
        JSONObject filter = new JSONObject();
        filter.put("equals", value);
        JSONObject condition = new JSONObject();
        condition.put("property", property);
        condition.put("select", filter);
        return condition;
    }
}
