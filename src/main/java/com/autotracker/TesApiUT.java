package com.autotracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.json.JSONArray;
import org.json.JSONObject;

import io.github.cdimascio.dotenv.Dotenv;

public class TesApiUT {
    public static void main(String[] args) {
        System.out.println("🕵️ Memulai misi penarikan Token API UT...");

        Dotenv dotenv = Dotenv.load();
        String username = dotenv.get("UT_NIM");
        String password = dotenv.get("UT_PASS");
        String serviceName = "moodle_mobile_app";

        String targetUrl = "https://elearning.ut.ac.id/login/token.php" +
                "?username=" + username +
                "&password=" + password +
                "&service=" + serviceName;

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(targetUrl)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            JSONObject jsonObj = new JSONObject(response.body());
            
            if (jsonObj.has("token")) {
                String tokenSakti = jsonObj.getString("token");
                cekProfilMahasiswa(tokenSakti); 
            } else {
                System.out.println("❌ Gagal dapet token.");
            }
        } catch (Exception e) {
            System.out.println("❌ Error: " + e.getMessage());
        }
    }

    private static void cekProfilMahasiswa(String token) {
        String urlSiteInfo = "https://elearning.ut.ac.id/webservice/rest/server.php" +
                "?wstoken=" + token +
                "&wsfunction=core_webservice_get_site_info" +
                "&moodlewsrestformat=json";

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(urlSiteInfo)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JSONObject profilJson = new JSONObject(response.body());

            if(profilJson.has("userid")) {
                int userId = profilJson.getInt("userid");
                String namaLengkap = profilJson.getString("fullname");

                System.out.println("\n🎉 BINGO! Data Berhasil ditemukan:");
                System.out.println("👤 Nama         : " + namaLengkap);
                System.out.println("🆔 User ID      : " + userId);
                
                System.out.println("\n📚 Mengambil daftar mata kuliah lu...");
                
                cekDaftarMatkul(token, userId);

            } else {
                System.out.println("\n❌ UT tidak mengirimkan User ID!");
            }
        } catch (Exception e) {
            System.out.println("❌ Gagal bedah profil: " + e.getMessage());
        }
    }

    private static void cekDaftarMatkul(String token, int userId) {
        String urlCourses = "https://elearning.ut.ac.id/webservice/rest/server.php" +
                "?wstoken=" + token +
                "&wsfunction=core_enrol_get_users_courses" +
                "&moodlewsrestformat=json" +
                "&userid=" + userId; 

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(urlCourses)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            System.out.println("📦 BALASAN MENTAH MATKUL: " + response.body());

            JSONArray matkulArray = new JSONArray(response.body());

            if (matkulArray.isEmpty()) {
                System.out.println("\n📭 Daftar Mata Kuliah belum diperbaharui, Semester belum dimulai!");
            } else {
                System.out.println("\n📋 Daftar Mata Kuliah telah diperbaharui. Semester telah dimulai, SELAMAT BELAJAR....:");
                for (int i = 0; i < matkulArray.length(); i++) {
                    JSONObject matkul = matkulArray.getJSONObject(i);
                    int idMatkul = matkul.getInt("id");
                    String namaMatkul = matkul.getString("fullname");
                    
                    System.out.println("- [" + idMatkul + "] " + namaMatkul);
                }
            }
        } catch (Exception e) {
            System.out.println("❌ Gagal Menarik Mata Kuliah: " + e.getMessage());
        }
    }
}