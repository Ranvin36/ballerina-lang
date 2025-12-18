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
 * <p>
 * This class centralizes SemVer logic so it can be reused by clients (Artifactory, Nexus, etc.).
 */
public final class SemVerUtils {
    private static final String SEMVER_PATTERN =
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                    "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                    "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$";
    private static final Pattern PATTERN = Pattern.compile(SEMVER_PATTERN);

    private SemVerUtils() {
    }

    /**
     * Quick validation that returns true when the provided version matches SemVer.
     */
    public static boolean isValidSemVer(String version) {
        if (version == null) return false;
        return PATTERN.matcher(version).matches();
    }

    /**
     * Parses the provided version string and returns a Matcher if it matches SemVer.
     * Throws IllegalArgumentException when the input is null or not a SemVer string.
     */
    public static Matcher parseSemVer(String version) {
        if (version == null) {
            throw new IllegalArgumentException("version is null");
        }
        Matcher m = PATTERN.matcher(version);
        if (m.matches()) return m;
        throw new IllegalArgumentException("Invalid SemVer string: " + version);
    }

    /**
     * Compares two SemVer strings using SemVer precedence rules.
     * Returns a negative integer, zero, or a positive integer as version1 is less than,
     * equal to, or greater than version2.
     */
    public static int compareSemVer(String version1, String version2) {
        Matcher matcher1 = parseSemVer(version1);
        Matcher matcher2 = parseSemVer(version2);

        int major1 = Integer.parseInt(matcher1.group(1));
        int minor1 = Integer.parseInt(matcher1.group(2));
        int patch1 = Integer.parseInt(matcher1.group(3));

        int major2 = Integer.parseInt(matcher2.group(1));
        int minor2 = Integer.parseInt(matcher2.group(2));
        int patch2 = Integer.parseInt(matcher2.group(3));

        if (major1 != major2) return Integer.compare(major1, major2);
        if (minor1 != minor2) return Integer.compare(minor1, minor2);
        if (patch1 != patch2) return Integer.compare(patch1, patch2);

        return comparePreReleases(matcher1, matcher2);
    }

    private static int comparePreReleases(Matcher a, Matcher b) {
        String pr1 = a.group(4);
        String pr2 = b.group(4);

        // absence of prerelease has higher precedence
        if (pr1 == null && pr2 == null) return 0;
        if (pr1 == null) return 1;
        if (pr2 == null) return -1;

        String[] parts1 = pr1.split("\\.");
        String[] parts2 = pr2.split("\\.");

        int minLen = Math.min(parts1.length, parts2.length);
        for (int i = 0; i < minLen; i++) {
            String x = parts1[i];
            String y = parts2[i];
            boolean xn = x.matches("0|[1-9]\\d*");
            boolean yn = y.matches("0|[1-9]\\d*");
            if (xn && yn) {
                int cmp = Integer.compare(Integer.parseInt(x), Integer.parseInt(y));
                if (cmp != 0) return cmp;
            } else if (xn && !yn) {
                return -1;
            } else if (!xn && yn) {
                return 1;
            } else {
                int cmp = x.compareTo(y);
                if (cmp != 0) return cmp;
            }
        }
        return Integer.compare(parts1.length, parts2.length);
    }
}
