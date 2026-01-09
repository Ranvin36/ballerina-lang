/*
 *  Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.ballerinalang.artifactory;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Type;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.ballerinalang.artifactory.utils.SemVerUtils;
import org.ballerinalang.central.client.CentralClientConstants;
import org.ballerinalang.central.client.LogFormatter;
import org.ballerinalang.central.client.Utils;
import org.ballerinalang.central.client.exceptions.CentralClientException;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Artifactory client to push and pull packages.
 */

public class ArtifactoryClient {
    private final String repositoryUrl;
    private final String username;
    private final String password;
    private final Path localRepoBase;
    private final PrintStream outStream;
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient();
    private final OkHttpClient client;
    private OkHttpClient httpClient() {
        return this.client;
    }

    private static final String[][] HEADER_MAP = {
            {"X-Checksum-Sha256", ".sha256", "SHA-256"},
            {"X-Checksum-Sha1", ".sha1", "SHA-1"},
            {"X-Checksum-Md5", ".md5", "MD5"}
    };

    private enum CompatibleRange {
        LATEST,
        LOCK_MAJOR,
        LOCK_MINOR,
        EXACT
    }


    /**
     * Create an instance of ArtifactoryClient.
     *
     * @param repositoryUrl the base URL of the Artifactory repository
     * @param username      the username for basic authentication
     * @param password      the password for basic authentication
     */
    public ArtifactoryClient(String repositoryUrl, String username, String password) {
        this(repositoryUrl, username, password, null, System.out);
    }

    /**
     * Create an instance of ArtifactoryClient with a custom local repository base path.
     *
     * @param repositoryUrl the base URL of the Artifactory repository
     * @param username      the username for basic authentication
     * @param password      the password for basic authentication
     * @param localRepoBase the custom local repository base path
     */
    public ArtifactoryClient(String repositoryUrl, String username, String password, Path localRepoBase) {
        this(repositoryUrl, username, password, localRepoBase, System.out);
    }

    /**
     * Create an instance of ArtifactoryClient with full configuration.
     *
     * @param repositoryUrl the base URL of the Artifactory repository
     * @param username      the username for basic authentication
     * @param password      the password for basic authentication
     * @param localRepoBase the custom local repository base path
     * @param outStream     the PrintStream to use for output (e.g., System.out)
     */
    public ArtifactoryClient(String repositoryUrl, String username, String password, Path localRepoBase,
                             PrintStream outStream) {
        this.repositoryUrl = repositoryUrl;
        this.username = username;
        this.password = password;
        this.outStream = outStream == null ? System.out : outStream;
        // prefer provided base, otherwise default under user.home
        if (localRepoBase != null) {
            this.localRepoBase = localRepoBase;
        } else {
            this.localRepoBase = Paths.get(System.getProperty("user.home"), ".ballerina", "repositories", "artifactory");
        }
        // configure a single client for this instance (reuses internal connection pool)
        this.client = HTTP_CLIENT.newBuilder()
                .connectTimeout(Duration.ofMinutes(5))
                .readTimeout(Duration.ofMinutes(5))
                .writeTimeout(Duration.ofMinutes(5))
                .build();
    }

    /**
     * Create a Request.Builder for the given repository-relative package path.
     *
     * @param packagePath repository relative path (e.g. "org/pkg/1.0.0/foo.bala").
     * @return an OkHttp {@link Request.Builder} configured with the full URL and basic auth header.
     */
    public Request.Builder createRequestBuilder(String packagePath) {
        // Use HttpUrl builder to safely join base URL and path segments and to handle encoding.
        HttpUrl base = HttpUrl.get(this.repositoryUrl);
        // packagePath may contain slashes; addPathSegments preserves segments correctly
        HttpUrl url = base.newBuilder().addPathSegments(packagePath).build();
        return new Request.Builder().url(url).header("Authorization", Credentials.basic(this.username, this.password));
    }


    /*
     * Method: fetchRemoteChecksums
     * Params:
     *   - packagePath : String (artifact path in repository)
     *   Description: Attempts a HEAD request to retrieve checksum headers; falls back gracefully.
     */

    private Map<String, String> fetchRemoteChecksums(String packagePath) throws IOException {
         Map<String, String> checksums = new HashMap<>();
         Request headReq = createRequestBuilder(packagePath).head().build();
        // reuse the configured per-instance client (do not rebuild per request)
         OkHttpClient client = this.httpClient();
         try (Response headResp = client.newCall(headReq).execute()) {
             if (headResp.isSuccessful()) {
                 for (String[] map : HEADER_MAP) {
                     String header = headResp.header(map[0]);
                     if (header != null && !header.isEmpty()) {
                         checksums.put(map[2], header.trim());
                     }
                 }
             }
             return checksums;
             
         } catch (IOException e) {
            outStream.println("Warning: Failed to fetch checksum headers via HEAD request: " + e.getMessage());
            return checksums;
         }
     }


    // This method extracts the filename from any valid Content-Disposition header, cleans it up, decodes it if needed, and rewrites it into a safe, predictable attachment; filename=<name> format.
    private static String sanitizeContentDispositionHeader(String header) {
        if (header == null || header.trim().isEmpty()) {
            return "";
        }
        String hd = header.trim();
        String filename = "";

        // Try RFC5987 filename* first (e.g., filename*=UTF-8''name%20with%20spaces.bala)
        Pattern pStar = Pattern.compile("filename\\*=[^']*''([^;\\r\\n]+)", Pattern.CASE_INSENSITIVE);
        Matcher mStar = pStar.matcher(hd);
        if (mStar.find()) {
            try {
                filename = URLDecoder.decode(mStar.group(1), StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {
                filename = mStar.group(1);
            }
        } else {
            // Fallback to filename="name" or filename=name
            Pattern p = Pattern.compile("filename\\s*=\\s*\"?([^\";\\r\\n]+)\"?", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(hd);
            if (m.find()) {
                filename = m.group(1);
            }
        }

        if (filename == null || filename.trim().isEmpty()) {
            return "";
        }
        // Remove any surrounding quotes just in case and ensure it's safe for filesystem APIs
        filename = filename.trim();
        if (filename.startsWith("\"") && filename.endsWith("\"") && filename.length() >= 2) {
            filename = filename.substring(1, filename.length() - 1);
        }
        // Strip any remaining surrounding quotes
        if ((filename.startsWith("\'") && filename.endsWith("\'")) || (filename.startsWith("\"") && filename.endsWith("\""))) {
            filename = filename.substring(1, filename.length() - 1);
        }
        // Finally, return a header string prefixed with 'attachment; filename='
        return "attachment; filename=" + filename;
    }


   /**
     * Download the specified .bala file from Artifactory and save it into the local bala cache.
     * The method expects the artifact to be available at the path
     * {@code <org>/<pkg>/<version>/<org>-<pkg>-any-<version>.bala} under the repository URL.
     *
     * @param org     organization name of the package (non-null, non-empty)
     * @param pkgName package name (non-null, non-empty)
     * @param version package version (non-null, non-empty)
     * @throws IOException              if there is a network or I/O failure while fetching or saving the BALA
     * @throws IllegalArgumentException when any input parameter is null or empty
     */
    public void pullPackage(String org, String pkgName, String version) throws IOException {
        // Validate API inputs
        if (org == null || org.trim().isEmpty()) {
            throw new IllegalArgumentException("Organization (org) must be provided and non-empty");
        }
        if (pkgName == null || pkgName.trim().isEmpty()) {
            throw new IllegalArgumentException("Package name must be provided and non-empty");
        }
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalArgumentException("Version must be provided and non-empty");
        }

        outStream.println("Pulling package from artifactory...");
        String requestPath = org + "/" + pkgName + "/" + version + "/" + org + "-" + pkgName + "-any-" + version + ".bala";
        Map<String, String> checksums = fetchRemoteChecksums(requestPath);
        Request.Builder requestBuilder = createRequestBuilder(requestPath).get();
        // reuse the configured per-instance client
        OkHttpClient client = this.httpClient();

        try (Response requestFile = client.newCall(requestBuilder.build()).execute()) {
            if (requestFile.isSuccessful()) {
                // build a platform-independent local path from the requestPath segments
                String[] segments = requestPath.split("/");
                Path artifactorypath = this.localRepoBase;
                for (String s : segments) {
                    artifactorypath = artifactorypath.resolve(s);
                }
                // The Central Utils.createBalaInHomeRepo expects the package path inside bala_cache
                // i.e. <user.home>/.ballerina/bala_cache/<org>/<pkgName>
                Path pkgPathInBalaCache = this.localRepoBase.resolve(org).resolve(pkgName);
                // Pass the response and delegate saving + extraction to central client's Utils
               try {
                   // Replace the content-disposition/newUrl handling in the pullPackage try-block with:
                   String rawContentDisposition = Optional.ofNullable(requestFile.header("Content-Disposition")).orElse("");
                   String contentDisposition = sanitizeContentDispositionHeader(rawContentDisposition);

                   String newUrl = "";
                   if (requestFile.request() != null && requestFile.request().url() != null) {
                       newUrl = requestFile.request().url().toString();
                   }

                   // Build the trueDigest value expected by Utils.createBalaInHomeRepo
                   String sha256Header = checksums.getOrDefault("SHA-256", "");
                   String trueDigest = sha256Header.isEmpty() ? "" : CentralClientConstants.SHA256 + sha256Header;

                   Utils.createBalaInHomeRepo(requestFile, pkgPathInBalaCache, org, pkgName,
                           false,
                           null,
                           newUrl,
                           contentDisposition,
                           outStream, new LogFormatter(),
                           trueDigest);


               } catch (CentralClientException e) {
                   throw new IOException("Failed to save and extract BALA into home repo: " + e.getMessage(), e);
               }


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
            throw new IOException(e.getMessage(), e);
        }
    }

    /*
     * Method: pullPackage (overload)
     * Params:
     *   - org : String      (organization name)
     *   - pkgName : String  (package name)
     * Description: Fetches the latest version (via getLatestVersion) and pulls that package.
     */

    /**
     * Fetch the latest available version for the given package and download it.
     * This overload chooses the semver-highest version available in the repository.
     *
     * @param org     organization name of the package (non-null, non-empty)
     * @param pkgName package name (non-null, non-empty)
     * @throws IOException              if resolution or download fails
     * @throws IllegalArgumentException when any input parameter is null or empty
     */
    public void pullPackage(String org, String pkgName) throws IOException {
        // Validate inputs
        if (org == null || org.trim().isEmpty()) {
            throw new IllegalArgumentException("Organization (org) must be provided and non-empty");
        }
        if (pkgName == null || pkgName.trim().isEmpty()) {
            throw new IllegalArgumentException("Package name must be provided and non-empty");
        }

        try {
            String latestVersion = getLatestVersion(org, pkgName);         
            if (latestVersion == null) {
                throw new IOException("No versions available for package: " + org + "/" + pkgName);
            }
            outStream.println("Latest version: " + latestVersion);
            pullPackage(org, pkgName, latestVersion);
        } catch (Exception e) {
            throw new IOException("Failed to pull package from artifactory : " + e.getMessage());
        }
    }


    /*
     * Params:
     *   - org : String          (organization name)
     *   - packageName : String  (package name)
     *   Description: Queries the repository for available versions and returns the existing versions.
     */

    /**
     * Query the repository for the list of existing versions for the package.
     * The client first attempts to read a {@code versions.json} at {@code <org>/<pkg>/versions.json}
     * and then falls back to a directory listing at {@code <org>/<pkg>}.
     *
     * @param org         organization name (non-null, non-empty)
     * @param packageName package name (non-null, non-empty)
     * @return a list of available version strings (may be empty if no versions found)
     * @throws IOException              on network or parsing errors
     * @throws IllegalArgumentException when any input parameter is null or empty
     */
    public List<String> getExistingVersion(String org, String packageName) throws IOException {
        // Validate inputs
        if (org == null || org.trim().isEmpty()) {
            throw new IllegalArgumentException("Organization (org) must be provided and non-empty");
        }
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("Package name must be provided and non-empty");
        }

        // Candidate paths: prefer an explicit versions.json, then fall back to a folder listing
        String[] candidatePaths = new String[]{
                org + "/" + packageName + "/versions.json",
                org + "/" + packageName
        };

        OkHttpClient client = this.httpClient();
        for (String requestPath : candidatePaths) {
            Request.Builder requestBuilder = createRequestBuilder(requestPath).get();
            Request request = requestBuilder.build();
            try (Response response = client.newCall(request).execute()) {
                // Diagnostic logging to help debug mismatches between client and server
                try {
                    outStream.println("GET " + request.url() + " -> HTTP/" + response.code());
                } catch (Exception ignored) {}
                 if (!response.isSuccessful()) {
                    // If 404 try next candidate; otherwise include body for diagnostics
                    if (response.code() == 404) {
                        continue;
                    }
                    String respBody = "";
                    try {
                        ResponseBody rb = response.body();
                        if (rb != null) respBody = rb.string();
                    } catch (Exception ignored) {}
                    throw new IOException("Failed to get versions from artifactory (" + requestPath + "): " + response.code() + " - " + respBody);
                }

                ResponseBody rb = response.body();
                if (rb == null) {
                    throw new IOException("Empty response body when fetching versions from " + requestPath);
                }
                String versionData = rb.string();
                // Debug: show raw versions.json body (if present)
                try { outStream.println("Response body for " + request.url() + ": " + versionData); } catch (Exception ignored) {}

                // Try to parse JSON and extract 'versions' array
                try {
                    JsonObject gsonObj = new Gson().fromJson(versionData, JsonObject.class);
                    JsonArray jsonVersions = gsonObj.getAsJsonArray("versions");
                    if (jsonVersions == null) {
                        // Not the expected JSON structure, treat as missing and try next candidate
                        continue;
                    }
                    Type listType = new TypeToken<List<String>>() {}.getType();
                    List<String> versions = new Gson().fromJson(jsonVersions, listType);
                    outStream.println(Arrays.toString(versions.toArray()));
                    if (versions == null || versions.isEmpty()) {
                        throw new IOException("No versions available for package: " + org + "/" + packageName);
                    }
                    return versions;
                } catch (Exception e) {
                    // JSON parse error — try next candidate
                    continue;
                }
            } catch (IOException e) {
                // Rethrow IOExceptions; these are network/IO errors we don't want to mask
                throw e;
            } catch (Exception e) {
                throw new IOException("Failed to get latest version from artifactory : " + e.getMessage(), e);
            }
        }

        // No versions found — treat as empty repository (allow push of initial version).
        outStream.println("No versions found for package: " + org + "/" + packageName + " — check repository URL/port and whether `versions.json` exists.");
        return Collections.emptyList();
    }

    /*
     * Params:
     *   - org : String          (organization name)
     *   - packageName : String  (package name)
     *   Description: Queries the existing versions and returns the latest according to SemVer.
     */
    /**
     * Return the semver-highest version available for the given package.
     *
     * @param org         organization name (non-null, non-empty)
     * @param packageName package name (non-null, non-empty)
     * @return the latest version string according to SemVer ordering, or {@code null} if no versions exist
     * @throws IOException              on network errors while retrieving versions
     * @throws IllegalArgumentException when any input parameter is null or empty
     */
    public String getLatestVersion(String org, String packageName) throws IOException {
        List<String> versions = getExistingVersion(org, packageName);
        if (versions != null && !versions.isEmpty()) {
            return Collections.max(versions, (v1, v2) -> SemVerUtils.compareSemVer(v1, v2));
        }
        return null;
    }


    /**
     * Resolve a concrete version to use for the given package according to a locking mode.
     *
     * @param org              organization name (non-null)
     * @param pkgName          package name (non-null)
     * @param requestedVersion the minimum or exact version requested (may be {@code null} to mean "latest")
     * @param mode             locking mode string: one of {@code "soft"}, {@code "medium"}, {@code "hard"}, {@code "locked"}.
     *                         If {@code null}, {@code "soft"} is assumed.
     * @return the selected concrete version string according to the requested compatibility rules
     * @throws IOException on network or resolution failures (including no compatible version found)
     */
    public String resolveVersion(String org, String pkgName, String requestedVersion, String mode) throws IOException {
        List<String> versions = getExistingVersion(org, pkgName);
        if (versions.isEmpty()) {
            throw new IOException("No versions found for package: " + org + "/" + pkgName);
        }
        return VersionResolver.resolve(versions, requestedVersion, mode);
    }

    // Helper: pick the highest semver from a list using SemVerUtils comparator
    private String pickHighestVersion(List<String> versions) {
        return Collections.max(versions, (v1, v2) -> SemVerUtils.compareSemVer(v1, v2));
    }

    // Helper: determine whether candidateVersion falls into compatible range relative to minVersion
    private boolean isInCompatibleRange(String minVersion, String candidateVersion, CompatibleRange range) {
        // If range is LATEST, everything is compatible
        if (range == CompatibleRange.LATEST) {
            return true;
        }

        // Parse numeric parts (major.minor.patch) ignoring pre-release/build metadata
        int[] minParts = parseSemVerParts(minVersion);
        int[] candParts = parseSemVerParts(candidateVersion);
        if (minParts == null || candParts == null) {
            // If parsing fails, conservatively include candidate only if compareSemVer >= 0
            return SemVerUtils.compareSemVer(candidateVersion, minVersion) >= 0;
        }

        // candidate must be >= minVersion
        if (SemVerUtils.compareSemVer(candidateVersion, minVersion) < 0) {
            return false;
        }

        switch (range) {
            case LOCK_MAJOR:
                return candParts[0] == minParts[0];
            case LOCK_MINOR:
                return candParts[0] == minParts[0] && candParts[1] == minParts[1];
            case EXACT:
                return candidateVersion.equals(minVersion);
            default:
                return false;
        }
    }

    // Parse semver string like "1.2.3" or "1.2.3-beta" into {major, minor, patch}
    private int[] parseSemVerParts(String version) {
        if (version == null) return null;
        // Strip pre-release/build suffix starting at first non-digit after patch
        String core = version;
        int idx = version.indexOf('-');
        if (idx != -1) core = version.substring(0, idx);
        idx = core.indexOf('+');
        if (idx != -1) core = core.substring(0, idx);

        String[] parts = core.split("\\.");
        if (parts.length < 3) return null;
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            // patch may include non-digit characters if malformed; parse up to non-digit
            String patchStr = parts[2];
            // strip any non-digit suffix
            int j = 0;
            while (j < patchStr.length() && Character.isDigit(patchStr.charAt(j))) j++;
            if (j == 0) return null;
            int patch = Integer.parseInt(patchStr.substring(0, j));
            return new int[]{major, minor, patch};
        } catch (NumberFormatException e) {
            return null;
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

    /**
     * Upload a .bala file to the configured Artifactory repository.
     * The method derives {@code org} and {@code pkg} and the {@code version} from the filename.
     * Expected filename format: {@code <org>-<pkg>-any-<version>.bala}
     *
     * @param balaPath path to the .bala file to upload
     * @throws IOException              if the file is missing, malformed, or upload fails
     * @throws IllegalArgumentException when {@code balaPath} is null
     */
    public void pushPackage(Path balaPath) throws IOException {
        // Validate input path
        if (balaPath == null) {
            throw new IllegalArgumentException("balaPath must be provided and non-null");
        }
        if (!Files.exists(balaPath)) {
            throw new IOException("BALA file not found at '" + balaPath + "'.");
        }

        // Derive version from filename using the last occurrence of "-any-" which is the stable delimiter
        String fileName = balaPath.getFileName().toString();
        int anyIdx = fileName.lastIndexOf("-any-");
        if (anyIdx == -1) {
            throw new IOException("Invalid BALA filename, expected '-any-' delimiter: " + fileName);
        }
        String versionWithExt = fileName.substring(anyIdx + "-any-".length());
        int firstDash = fileName.indexOf('-');
        if (firstDash == -1 || firstDash >= anyIdx) {
            throw new IOException("Invalid BALA filename, cannot determine org/pkg from filename: " + fileName);
        }
        String org = fileName.substring(0, firstDash);
        String pkg = fileName.substring(firstDash + 1, anyIdx);
        if (org.trim().isEmpty() || pkg.trim().isEmpty()) {
            throw new IOException("Failed to extract org/pkg from filename: " + fileName);
        }
        String version = versionWithExt.endsWith(".bala") ? versionWithExt.substring(0, versionWithExt.length() - ".bala".length()) : versionWithExt;
        // Validate SemVer and refuse to push invalid versions (e.g., leading zeros like 01.0.1)
        if (!SemVerUtils.isValidSemVer(version)) {
            throw new IOException("Invalid semantic version extracted from BALA filename: '" + version + "'. Upload aborted.");
        }
        // Check against existing latest version (if any). If no existing version, allow push.
        String latestVersion = getLatestVersion(org, pkg);
        if (latestVersion != null) {
            int compareSemver = SemVerUtils.compareSemVer(version, latestVersion);
            // compareSemver < 0 => version < latestVersion
            // compareSemver == 0 => equal
            // Only allow push when compareSemver > 0
            if (compareSemver < 0) {
                throw new IOException("Cannot push version '" + version + "' as it is older than the latest existing version '" + latestVersion + "'. Upload aborted.");
            } else if (compareSemver == 0) {
                throw new IOException("Version '" + version + "' already exists as the latest version '" + latestVersion + "'. Upload aborted.");
            }
        }
        try {
            RequestBody requestBody = RequestBody.create(balaPath.toFile(), MediaType.parse("application/octet-stream"));
            String targetPath = org + "/" + pkg + "/" + version + "/" + balaPath.toFile().getName();

            Request.Builder reqBuilder = createRequestBuilder(targetPath);
            Request request = reqBuilder.put(requestBody).build();

            // reuse the configured per-instance client
            OkHttpClient client = this.httpClient();
            try (Response response = client.newCall(request).execute()) {
                 if (response.isSuccessful()) {
                    outStream.println("Package pushed successfully to artifactory with status code: " + response.code());
                } else {
                    throw new IOException("Failed to push package to artifactory: " + response.message());
                }
             }
         } catch (IOException ioe) {
             // propagate IOExceptions (including invalid version) to the caller
             throw ioe;
         } catch (Exception e) {
             // wrap other unexpected exceptions as IOExceptions so caller gets a failure
             throw new IOException("Failed to push package to artifactory : " + e.getMessage(), e);
         }
    }
}

