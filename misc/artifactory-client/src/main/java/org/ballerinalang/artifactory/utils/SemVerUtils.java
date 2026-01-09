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

package org.ballerinalang.artifactory.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods for parsing and comparing Semantic Version (SemVer) strings.
 *
 * This class centralizes SemVer logic so it can be reused by clients (Artifactory, Nexus, etc.).
 */
public final class SemVerUtils {
    // The SemVer regular expression follows the Semantic Versioning 2.0.0 spec.
    // It captures:
    //  group(1) = major
    //  group(2) = minor
    //  group(3) = patch
    //  group(4) = pre-release (optional)
    //  group(5) = build metadata (optional, ignored for precedence)
    // The pattern enforces no leading zeros on numeric identifiers (except zero itself).
    private static final String SEMVER_PATTERN =
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                    "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                    "(?:\\+([0-9A-ZaZ-]+(?:\\.[0-9A-ZaZ-]+)*))?$";
    private static final Pattern PATTERN = Pattern.compile(SEMVER_PATTERN);

    private SemVerUtils() {
    }

    /**
     * Quick validation that returns true when the provided version matches SemVer.
     *
     * This is useful to reject clearly invalid strings early (e.g. "01.2.3", "foo").
     */
    public static boolean isValidSemVer(String version) {
        // Quick null check then try the precompiled regex. Returns true for valid SemVer strings.
        if (version == null) return false;
        return PATTERN.matcher(version).matches();
    }

    /**
     * Parses the provided version string and returns a Matcher if it matches SemVer.
     * Throws IllegalArgumentException when the input is null or not a SemVer string.
     *
     * Use the returned Matcher to extract numeric groups (major/minor/patch) and
     * optional prerelease/build metadata groups.
     */
    public static Matcher parseSemVer(String version) {
        if (version == null) {
            throw new IllegalArgumentException("version is null");
        }
        // Run the regex matcher once and return it for downstream group extraction.
        Matcher m = PATTERN.matcher(version);
        if (m.matches()) return m;
        throw new IllegalArgumentException("Invalid SemVer string: " + version);
    }

    /**
     * Compares two SemVer strings using SemVer precedence rules.
     * Returns a negative integer, zero, or a positive integer as version1 is less than,
     * equal to, or greater than version2.
     *
     * Comparison rules implemented:
     *  - Compare major, then minor, then patch numerically.
     *  - If numeric parts are equal, compare pre-release identifiers:
     *      * A version without a pre-release has higher precedence than one with a pre-release
     *        (e.g., 1.0.0 > 1.0.0-alpha).
     *      * Pre-release identifiers are compared dot-separated identifier by identifier.
     *        Numeric identifiers are compared numerically. Alphanumeric identifiers are compared
     *        lexically in ASCII sort order. Numeric identifiers have lower precedence than
     *        alphanumeric identifiers when compared (e.g., 1 < alpha).
     *  - Build metadata (the + part) is ignored for precedence.
     */
    public static int compareSemVer(String version1, String version2) {
        // Parse both versions using the regex to extract core numeric fields and prerelease.
        Matcher matcher1 = parseSemVer(version1);
        Matcher matcher2 = parseSemVer(version2);

        // Extract numeric core fields (guaranteed numeric by the regex)
        int major1 = Integer.parseInt(matcher1.group(1));
        int minor1 = Integer.parseInt(matcher1.group(2));
        int patch1 = Integer.parseInt(matcher1.group(3));

        int major2 = Integer.parseInt(matcher2.group(1));
        int minor2 = Integer.parseInt(matcher2.group(2));
        int patch2 = Integer.parseInt(matcher2.group(3));

        // Numeric precedence: compare core version fields in order of significance.
        if (major1 != major2) return Integer.compare(major1, major2);
        if (minor1 != minor2) return Integer.compare(minor1, minor2);
        if (patch1 != patch2) return Integer.compare(patch1, patch2);

        // If the numeric core is equal, resolve ordering via prerelease identifiers.
        return comparePreReleases(matcher1, matcher2);
    }

    /**
     * Compare two prerelease strings (matcher groups) according to SemVer rules.
     *
     * Behavior and corner-cases handled:
     *  - If both are absent -> equal precedence.
     *  - If only one is present -> the one without prerelease has higher precedence.
     *  - When both present, split by '.' and compare each identifier:
     *      * If both identifiers are numeric -> numeric comparison (no leading zeros permitted by pattern).
     *      * If one is numeric and the other is alphanumeric -> numeric has lower precedence.
     *      * If both are non-numeric -> lexical (ASCII) comparison.
     *  - If all compared identifiers are equal but lengths differ, the longer identifier list has higher precedence
     *    if the extra identifiers are considered greater by SemVer rules (here we return compare of lengths).
     */
    private static int comparePreReleases(Matcher a, Matcher b) {
        String pr1 = a.group(4);
        String pr2 = b.group(4);

        // If either prerelease is missing, the version without prerelease has higher precedence
        // (e.g., 1.0.0 > 1.0.0-alpha). Handle presence/absence first as a fast path.
        if (pr1 == null && pr2 == null) return 0;
        if (pr1 == null) return 1;
        if (pr2 == null) return -1;

        // Both have prerelease fields. Split into dot-separated identifiers and compare one-by-one.
        String[] parts1 = pr1.split("\\.");
        String[] parts2 = pr2.split("\\.");

        int minLen = Math.min(parts1.length, parts2.length);
        for (int i = 0; i < minLen; i++) {
            String x = parts1[i];
            String y = parts2[i];

            // Determine whether each identifier is numeric (regex guarantees no leading zeros)
            boolean xn = x.matches("0|[1-9]\\d*");
            boolean yn = y.matches("0|[1-9]\\d*");

            if (xn && yn) {
                // Both numeric: compare numerically (not lexically)
                int cmp = Integer.compare(Integer.parseInt(x), Integer.parseInt(y));
                if (cmp != 0) return cmp;
            } else if (xn && !yn) {
                // Numeric identifiers have lower precedence than non-numeric ones
                return -1;
            } else if (!xn && yn) {
                return 1;
            } else {
                // Both non-numeric: compare ASCII/lexical order
                int cmp = x.compareTo(y);
                if (cmp != 0) return cmp;
            }
        }
        // All compared identifiers are equal. The shorter identifier list has lower precedence.
        return Integer.compare(parts1.length, parts2.length);
    }
}
