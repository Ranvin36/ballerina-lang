package org.ballerinalang.artifactory;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class VersionResolverTest {

    @Test
    public void testResolveLatestWhenRequestedNull() throws Exception {
        List<String> versions = Arrays.asList("1.0.0", "1.2.0", "2.0.0");
        String resolved = VersionResolver.resolve(versions, null, "soft");
        Assert.assertEquals(resolved, "2.0.0");
    }

    @Test
    public void testResolveSoftMode() throws Exception {
        List<String> versions = Arrays.asList("1.0.1", "1.0.2", "1.1.0", "2.0.0");
        String resolved = VersionResolver.resolve(versions, "1.0.1", "soft");
        // soft: lock major -> highest 1.x >= 1.0.1 -> 1.1.0
        Assert.assertEquals(resolved, "1.1.0");
    }

    @Test
    public void testResolveMediumMode() throws Exception {
        List<String> versions = Arrays.asList("1.0.1", "1.0.2", "1.1.0", "2.0.0");
        String resolved = VersionResolver.resolve(versions, "1.0.1", "medium");
        // medium: lock minor -> highest 1.0.x >= 1.0.1 -> 1.0.2
        Assert.assertEquals(resolved, "1.0.2");
    }

    @Test
    public void testResolveHardModeExactFound() throws Exception {
        List<String> versions = Arrays.asList("1.0.1", "1.0.2");
        String resolved = VersionResolver.resolve(versions, "1.0.2", "hard");
        Assert.assertEquals(resolved, "1.0.2");
    }

    @Test(expectedExceptions = IOException.class)
    public void testResolveHardModeExactNotFound() throws Exception {
        List<String> versions = Arrays.asList("1.0.1", "1.0.2");
        VersionResolver.resolve(versions, "1.0.3", "hard");
    }

    // New tests: no compatible versions in soft/medium modes
    @Test(expectedExceptions = IOException.class)
    public void testResolveSoftModeNoCompatible() throws Exception {
        // available versions are all major 2.x, requested is 1.0.x -> soft should fail
        List<String> versions = Arrays.asList("2.0.0", "2.1.0");
        VersionResolver.resolve(versions, "1.0.0", "soft");
    }

    @Test(expectedExceptions = IOException.class)
    public void testResolveMediumModeNoCompatible() throws Exception {
        // available versions include 1.1.x but requested 1.0.x -> medium should fail
        List<String> versions = Arrays.asList("1.1.0", "1.1.2");
        VersionResolver.resolve(versions, "1.0.0", "medium");
    }
}
