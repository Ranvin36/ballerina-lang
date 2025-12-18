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
    private static final String[][] HEADER_MAP = {
            {"X-Checksum-Sha256", ".sha256", "SHA-256"},
            {"X-Checksum-Sha1", ".sha1", "SHA-1"},
            {"X-Checksum-Md5", ".md5", "MD5"}
    };

    public ArtifactoryClient(String repositoryUrl, String username, String password) {
        this(repositoryUrl, username, password, null);
    }

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
//        // optional environment override for a BALA to push
//        String env = System.getenv("BALLERINA_BALA_PATH");
//        this.envBalaPath = (env != null && !env.isEmpty()) ? Paths.get(env) : null;
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


    /*
     * Method: pullPackage
     * Params:
     *   - org : String       (organization name)
     *   - pkgName : String   (package name)
     *   - version : String   (package version)
     * Description: Downloads the specified .bala file from Artifactory and saves it to the local repo.
     */

    public void pullPackage(String org, String pkgName, String version) throws IOException {
        System.out.println("Pulling package from artifactory...");
        String requestPath = org + "/" + pkgName + "/" + version + "/" + org + "-" + pkgName + "-any-" + version + ".bala";
        Map<String, String> checksums = fetchRemoteChecksums(requestPath);
        Request.Builder requestBuilder = createRequestBuilder(requestPath).get();
        OkHttpClient client = this.httpClient();

        try (Response requestFile = client.newCall(requestBuilder.build()).execute()) {
            if (requestFile.isSuccessful()) {
                System.out.println("Package pulled successfully from artifactory with status code: " + requestFile.code());
                // build a platform-independent local path from the requestPath segments
                String[] segments = requestPath.split("/");
                Path artifactorypath = this.localRepoBase;
                for (String s : segments) {
                    artifactorypath = artifactorypath.resolve(s);
                }
                ChecksumUtils.saveResponseToFile(requestFile, artifactorypath, checksums);

            } else {
                // Read body (if any) for debugging and include headers
                String respBody = "";
                try {
                    ResponseBody rb = requestFile.body();
                    if (rb != null) respBody = rb.string();
                } catch (Exception e) {
                    respBody = "<error reading body: " + e.getMessage() + ">";
                }

                throw new IOException("Failed to pull package from artifactory: " + requestFile.code() + " - " + respBody);
            }
        } catch (IOException e) {
            throw new IOException("Failed to pull package from artifactory : " + e.getMessage(), e);
        }
    }

    /*
     * Method: pullPackage (overload)
     * Params:
     *   - org : String      (organization name)
     *   - pkgName : String  (package name)
     * Description: Fetches the latest version (via getLatestVersion) and pulls that package.
     */

    public void pullPackage(String org, String pkgName) throws IOException {
        try {
            String latestVersion = getLatestVersion(org, pkgName);
            System.out.println("Latest version: " + latestVersion);
            pullPackage(org, pkgName, latestVersion);
        } catch (Exception e) {
            throw new IOException("Failed to pull package from artifactory : " + e.getMessage());
        }
    }


    /*
     * Params:
     *   - org : String          (organization name)
     *   - packageName : String  (package name)
     *   Description: Queries the repository for available versions and returns the latest according to SemVer.
     */

    public String getLatestVersion(String org, String packageName) throws IOException {
        String requestPath = org + "/" + packageName;
        Request.Builder requestBuilder = createRequestBuilder(requestPath).get();
        OkHttpClient client = this.httpClient();
        try (Response response = client.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get latest version from artifactory: " + response.message());
            }
            ResponseBody rb = response.body();
            if (rb == null) {
                throw new IOException("Empty response body when fetching versions");
            }
            String versionData = rb.string();
            System.out.println("Latest version fetched successfully from artifactory with status code: " + response.code());
            JsonObject gsonObj = new Gson().fromJson(versionData, JsonObject.class);
            JsonArray jsonVersions = gsonObj.getAsJsonArray("versions");
            if (jsonVersions == null) {
                throw new IOException("No 'versions' array found in response");
            }
            Type listType = new TypeToken<List<String>>() {}.getType();
            List<String> versions = new Gson().fromJson(jsonVersions, listType);
            if (versions == null || versions.isEmpty()) {
                throw new IOException("No versions available for package: " + org + "/" + packageName);
            }
            return Collections.max(versions, (v1, v2) -> SemVerUtils.compareSemVer(v1, v2));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to get latest version from artifactory : " + e.getMessage(), e);
        }
    }

    /*
     * Method: pushPackage
     * Params:
     *   - org : String      (organization name)
     *   - pkg : String      (package name)
     *   - version : String  (package version)
     * Description: Uploads a .bala file to Artifactory with checksum headers.
     */

    public void pushPackage(String org, String pkg, String version,Path balaPath) throws IOException {

        if (balaPath == null || !Files.exists(balaPath)) {
            throw new IOException("BALA file not found. Set BALLERINA_BALA_PATH environment variable or place the BALA at ./target/bala/<org>-<pkg>-any-<version>.bala");
        }

        System.out.println("Pushing package to artifactory... using BALA: " + balaPath);
        try {
            RequestBody requestBody = RequestBody.create(balaPath.toFile(), MediaType.parse("application/octet-stream"));
            String targetPath = org + "/" + pkg + "/" + version + "/" + balaPath.toFile().getName();
            System.out.println(targetPath);
            String md5CheckSum = Objects.toString(ChecksumUtils.calculateChecksum(balaPath.toString(), "MD5"), "");
            String sha256CheckSum = Objects.toString(ChecksumUtils.calculateChecksum(balaPath.toString(), "SHA-256"), "");
            String sha1CheckSum = Objects.toString(ChecksumUtils.calculateChecksum(balaPath.toString(), "SHA-1"), "");
            System.out.println("Calculated MD5 checksum: " + md5CheckSum + " Calculated SHA1 checksum: " + sha1CheckSum + " Calculated SHA256 checksum: " + sha256CheckSum);

            Request.Builder reqBuilder = createRequestBuilder(targetPath);
            if (!md5CheckSum.isEmpty()) reqBuilder.header("X-Checksum-Md5", md5CheckSum);
            if (!sha1CheckSum.isEmpty()) reqBuilder.header("X-Checksum-Sha1", sha1CheckSum);
            if (!sha256CheckSum.isEmpty()) reqBuilder.header("X-Checksum-Sha256", sha256CheckSum);
            Request request = reqBuilder.put(requestBody).build();

            OkHttpClient client = this.httpClient();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    System.out.println("Package pushed successfully to artifactory with status code: " + response.code());
                } else {
                    throw new IOException("Failed to push package to artifactory: " + response.message());
                }
            }
        } catch (Exception e) {
            System.out.println("Failed to push package to artifactory : " + e.getMessage());
        }
    }
}
