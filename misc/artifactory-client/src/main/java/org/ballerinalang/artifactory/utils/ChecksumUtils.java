package org.ballerinalang.artifactory.utils;

import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Utility class for checksum calculations.
 */

public class ChecksumUtils {

    /*
     * Params:
     *   - targetPath : Path       (target path where final file will be placed)
     *   Description: Creates a temporary file in the same directory as targetPath when possible.
     *                If the parent directory cannot be used, falls back to the system temp directory.
     */
    public static Path createTempFile(Path targetPath) throws IOException {
        try{
            Path parent = targetPath.getParent();
            if(parent != null){
                Files.createDirectories(parent);
                return Files.createTempFile(parent,"download-",".bala");
            }
        }
        catch (Exception e){
            // Log the fallback reason and continue to create temp file in system temp dir
            System.err.println("createTempFile fallback: " + e.getMessage());
        }

        return Files.createTempFile("download-",".bala");
    }

    /*
     * Params:
     *   - response : Response                (HTTP response containing the artifact body)
     *   - finalTarget : Path                 (final target path where the artifact should be moved)
     *   - checksums : Map<String,String>     (map of checksum algorithm -> expected checksum)
     *   Description: Saves the response body to a temporary file, verifies provided checksums
     *                (if any) against the downloaded file and moves the file to finalTarget
     *                if verification succeeds. Cleans up temporary files on failure.
     */
    public static void saveResponseToFile(Response response, Path finalTarget, Map<String,String> checksums) throws IOException {
        if (response == null || response.body() == null) {
            throw new IOException("Empty response or no response body");
        }

        Path tmp = null;
        try {
            tmp = createTempFile(finalTarget);
            // Stream response body to temp file
            long contentLength = -1L;
            try (ResponseBody body = response.body(); InputStream in = body.byteStream()) {
                try {
                    contentLength = body.contentLength();
                } catch (Exception ignored) {}
                System.out.println("Response content-length header: " + contentLength);
                long bytesCopied = Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Bytes actually copied to temp file: " + bytesCopied);

                // If nothing was copied, fail early to surface the problem (empty download)
                if (bytesCopied == 0L) {
                    try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
                    throw new IOException("Downloaded artifact is empty (0 bytes). Server may have returned empty body or request lacked permission. Check server response and credentials.");
                }
            }

            // If checksums map is empty, accept file and move it
            if (checksums == null || checksums.isEmpty()) {
                Files.createDirectories(finalTarget.getParent());
                Files.move(tmp, finalTarget, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Saved artifact to " + finalTarget + " (no checksums to verify)");
                return;
            }

            // Verify each checksum entry; keys expected to be algorithm names like "SHA-256", "SHA-1", "MD5"
            for (Map.Entry<String,String> entry : checksums.entrySet()) {
                String alg = entry.getKey();
                String expected = entry.getValue();
                if (expected == null || expected.trim().isEmpty()) continue; // skip empty

                // Calculate checksum
                String calculated = calculateChecksum(tmp.toString(), alg);
                if (calculated == null) {
                    throw new IOException("Failed to calculate " + alg + " for downloaded artifact");
                }

                // Normalize both values (lowercase, strip non-hex)
                String calcNorm = calculated.trim().toLowerCase().replaceAll("[^0-9a-f]", "");
                String expNorm = expected.trim().toLowerCase().replaceAll("[^0-9a-f]", "");

                System.out.println(alg + " calculated (normalized): " + calcNorm);
                System.out.println(alg + " expected   (normalized): " + expNorm);

                if (!calcNorm.equals(expNorm)) {
                    // Delete temp file and throw
                    try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
                    throw new IOException(alg + " checksum mismatch for downloaded artifact");
                }
            }

            // All verifications passed -> move to final target
            Files.createDirectories(finalTarget.getParent());
            Files.move(tmp, finalTarget, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Saved artifact to " + finalTarget);

        } catch (IOException e) {
            // Ensure temp file is removed on error
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    /*
     * Params:
     *   - filePath : String     (path to the file to calculate checksum for)
     *   - algorithm : String    (checksum algorithm name, e.g. "SHA-256", "MD5")
     *   Description: Calculates and returns the hex-encoded checksum of the file using the specified algorithm.
     *                Returns null on error.
     */
    public static String calculateChecksum(String filePath, String algorithm) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance(algorithm);
            java.io.FileInputStream fis = new java.io.FileInputStream(filePath);
            byte[] byteArray = new byte[1024];
            int bytesRead;
            while ((bytesRead = fis.read(byteArray)) != -1) {
                digest.update(byteArray, 0, bytesRead);
            }
            fis.close();
            byte[] checksumBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : checksumBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
        catch (NoSuchAlgorithmException noSuchAlgorithmException){
            System.out.println("Unsupported checksum algorithm: " + algorithm);
            return null;
        }
        catch (IOException e) {
            System.out.println("Error calculating checksum: " + e.getMessage());
            return null;
        }
    }
}
