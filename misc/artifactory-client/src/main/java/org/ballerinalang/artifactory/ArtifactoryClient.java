package org.ballerinalang.artifactory;

import com.google.gson.JsonArray;
import okhttp3.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import org.ballerinalang.artifactory.utils.ChecksumUtils;
import org.ballerinalang.artifactory.utils.SemVerUtils;

/**
 * Artifactory client to push and pull packages.
 */

public class ArtifactoryClient {
    private final String repositoryUrl;
    private final String username;
    private final String password;
    private final Path localRepoBase; // base directory for local artifactory cache
    private final Path envBalaPath; // optional BALA path from environment
    private static final String[][] HEADER_MAP = {
            {"X-Checksum-Sha256", ".sha256", "SHA-256"},
            {"X-Checksum-Sha1", ".sha1", "SHA-1"},
            {"X-Checksum-Md5", ".md5", "MD5"}
    };

    public ArtifactoryClient(String repositoryUrl, String username, String password) {
        this(repositoryUrl, username, password, null);
    }

    /**
     * New constructor which accepts an explicit local repo base path. If localRepoBase is null,
     * it defaults to {user.home}/.ballerina/repositories/artifactory
     */
    public ArtifactoryClient(String repositoryUrl, String username, String password, Path localRepoBase) {
        this.repositoryUrl = repositoryUrl;
        this.username = username;
        this.password = password;
        // prefer provided base, otherwise default under user.home
        if (localRepoBase != null) {
            this.localRepoBase = localRepoBase;
        } else {
            this.localRepoBase = Paths.get(System.getProperty("user.home"), ".ballerina", "repositories", "artifactory");
        }
        // optional environment override for a BALA to push
        String env = System.getenv("BALLERINA_BALA_PATH");
        this.envBalaPath = (env != null && !env.isEmpty()) ? Paths.get(env) : null;
    }

    /*
     * Method: httpClient
     * Params: none
     * Description: Builds and returns an OkHttpClient with configured timeouts.
     * NOTE: Method body is commented out per request.
     */

    public OkHttpClient httpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMinutes(5))
                .readTimeout(Duration.ofMinutes(5))
                .writeTimeout(Duration.ofMinutes(5))
                .build();
    }


    /*
     * Method: createRequestBuilder
     * Params:
     *   - packagePath : String  (path appended to repository URL)
     * Description: Creates an OkHttp Request.Builder for the given package path and sets Authorization header.
     */

    public Request.Builder createRequestBuilder(String packagePath) {
        String url = this.repositoryUrl + "/" + packagePath;
        System.out.println("Request URL: " + url);
        return new Request.Builder().url(url).header("Authorization", Credentials.basic(this.username, this.password));
    }


    /*
     * Method: fetchRemoteChecksums
     * Params:
     *   - packagePath : String (artifact path in repository)
     *   Description: Attempts a HEAD request to retrieve checksum headers; falls back gracefully.
     */

    private Map<String, String> fetchRemoteChecksums(String packagePath) {
        Map<String, String> checksums = new HashMap<>();
        Request headReq = createRequestBuilder(packagePath).head().build();
        try (Response headResp = httpClient().newCall(headReq).execute()) {
            if (headResp.isSuccessful()) {
                for (String[] map : HEADER_MAP) {
                    String header = headResp.header(map[0]);
                    System.out.println(map[2] + " : " + header);
                    if (header != null && !header.isEmpty()) {
                        checksums.put(map[2], header.trim());
                    }
                }
            }
            return checksums;
        } catch (IOException e) {
            System.err.println("HEAD request failed for " + packagePath + ": " + e.getMessage());
            return checksums;
            // continue to fallback GETs
        }
    }


}
