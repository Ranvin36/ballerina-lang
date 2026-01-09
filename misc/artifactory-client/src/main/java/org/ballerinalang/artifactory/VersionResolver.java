package org.ballerinalang.artifactory;

import org.ballerinalang.artifactory.utils.SemVerUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pure utility for resolving versions according to locking modes.
 */
public final class VersionResolver {
    private VersionResolver() { }

    public enum CompatibleRange {
        LATEST, LOCK_MAJOR, LOCK_MINOR, EXACT
    }

    public static String resolve(List<String> versions, String requestedVersion, String mode) throws IOException {
        if (versions == null || versions.isEmpty()) {
            throw new IOException("No versions provided");
        }
        String normMode = mode == null ? "soft" : mode.trim().toLowerCase(Locale.ROOT);
        CompatibleRange compatibleRange;
        switch (normMode) {
            case "hard":
            case "locked":
                compatibleRange = CompatibleRange.EXACT;
                break;
            case "medium":
                compatibleRange = CompatibleRange.LOCK_MINOR;
                break;
            case "soft":
            default:
                compatibleRange = CompatibleRange.LOCK_MAJOR;
                break;
        }

        // If exact and requested provided, return it if exists
        if (compatibleRange == CompatibleRange.EXACT && requestedVersion != null && !requestedVersion.isEmpty()) {
            if (versions.contains(requestedVersion)) {
                return requestedVersion;
            }
            throw new IOException("Exact version requested not found: " + requestedVersion);
        }

        if (requestedVersion == null || requestedVersion.isEmpty()) {
            return pickHighest(versions);
        }

        List<String> compatible = new ArrayList<>();
        for (String v : versions) {
            if (isCompatible(requestedVersion, v, compatibleRange)) {
                compatible.add(v);
            }
        }
        if (!compatible.isEmpty()) {
            return pickHighest(compatible);
        }

        // No compatible version found
        switch (compatibleRange) {
            case EXACT:
                throw new IOException("Exact version requested not found: " + requestedVersion);
            case LOCK_MAJOR:
                throw new IOException("No version with matching major component found for " + requestedVersion + " (soft mode)");
            case LOCK_MINOR:
                throw new IOException("No version with matching major.minor components found for " + requestedVersion + " (medium mode)");
            default:
                throw new IOException("No compatible version found for " + requestedVersion);
        }
    }

    private static String pickHighest(List<String> versions) {
        return Collections.max(versions, (v1, v2) -> SemVerUtils.compareSemVer(v1, v2));
    }

    private static boolean isCompatible(String minVersion, String candidate, CompatibleRange range) {
        if (range == CompatibleRange.LATEST) return true;
        int[] minParts = parse(minVersion);
        int[] candParts = parse(candidate);
        if (minParts == null || candParts == null) {
            return SemVerUtils.compareSemVer(candidate, minVersion) >= 0;
        }
        if (SemVerUtils.compareSemVer(candidate, minVersion) < 0) return false;
        switch (range) {
            case LOCK_MAJOR:
                return candParts[0] == minParts[0];
            case LOCK_MINOR:
                return candParts[0] == minParts[0] && candParts[1] == minParts[1];
            case EXACT:
                return candidate.equals(minVersion);
            default:
                return false;
        }
    }

    private static int[] parse(String v) {
        if (v == null) return null;
        String core = v;
        int idx = v.indexOf('-');
        if (idx != -1) core = v.substring(0, idx);
        idx = core.indexOf('+');
        if (idx != -1) core = core.substring(0, idx);
        String[] parts = core.split("\\.");
        if (parts.length < 3) return null;
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            String patchStr = parts[2];
            int j = 0;
            while (j < patchStr.length() && Character.isDigit(patchStr.charAt(j))) j++;
            if (j == 0) return null;
            int patch = Integer.parseInt(patchStr.substring(0, j));
            return new int[]{major, minor, patch};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

