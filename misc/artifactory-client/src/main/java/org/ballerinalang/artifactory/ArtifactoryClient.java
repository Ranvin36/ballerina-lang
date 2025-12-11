package org.ballerinalang.artifactory;

import com.google.gson.JsonArray;
import okhttp3.Request;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.Credentials;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

import com.google.gson.Gson;
//import com.google.gson.JsonArray;
//import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Artifactory client to push and pull packages.
 */

public class ArtifactoryClient {
    private final String repositoryUrl;
    private final String username;
    private final String password;

    public ArtifactoryClient(String repositoryUrl,String username, String password) {
        this.repositoryUrl = repositoryUrl;
        this.username=username;
        this.password=password;

    }

//    Setup client to send requests to artifactory server
    public OkHttpClient httpClient() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMinutes(5))
                .readTimeout(Duration.ofMinutes(5))
                .writeTimeout(Duration.ofMinutes(5))
                .build();
        return client;

    }

    public Request.Builder createRequestBuilder(String packagePath) {
        String url = this.repositoryUrl + "/" + packagePath;
        System.out.println("Request URL: " + url);
        return new Request.Builder().url(url).header("Authorization",Credentials.basic(this.username, this.password));
    }

    public void pullPackage(String org, String pkgName, String version) throws IOException {
        try {
            System.out.println("Pulling package from artifactory...");
            String requestPath = org + "/" + pkgName + "/" + version + "/" + pkgName + "-" + version + ".bala";

            Request.Builder requestBuilder = createRequestBuilder(requestPath).get();
            OkHttpClient client = this.httpClient();
            Response requestFile = client.newCall(requestBuilder.build()).execute();
            if(requestFile.isSuccessful()){
                System.out.println("Package pulled successfully from artifactory with status code: " + requestFile.code());
            } else {
                throw new IOException("Failed to pull package from artifactory: " + requestFile.message());
            }
        }
        catch (IOException e){
            throw new IOException("Failed to pull package from artifactory : " + e.getMessage());
        }
    }
    

}
