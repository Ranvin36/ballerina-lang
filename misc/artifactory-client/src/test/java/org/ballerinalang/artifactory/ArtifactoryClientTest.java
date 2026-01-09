package org.ballerinalang.artifactory;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ArtifactoryClientTest {
    private MockWebServer server;
    // baos can remain a field if you want to inspect logs in multiple tests
    private ByteArrayOutputStream baos;
    private PrintStream ps;

    @BeforeMethod
    void setup() throws Exception {
        server = new MockWebServer();
        server.start();
        baos = new ByteArrayOutputStream();
        ps = new PrintStream(baos);
    }

    @AfterMethod
    void tearDown() throws Exception {
        server.shutdown();
        ps.close();
    }

    @Test(timeOut = 5000)
    public void testGetExistingVersion_withVersionsJson() throws Exception {
        final String body = "{\"versions\": [\"1.0.1\", \"1.0.2\", \"1.1.0\"]}";
        Dispatcher dispatcher = new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    ps.println("Dispatcher received request: " + request.getRequestUrl());
                    System.out.println("Dispatcher received request: " + request.getRequestUrl());
                } catch (Exception ignored) {}
                // Always respond with our versions JSON for any incoming request.
                return new MockResponse().setResponseCode(200).setBody(body)
                        .addHeader("Content-Type", "application/json");
            }
        };
        server.setDispatcher(dispatcher);

        String baseUrl = server.url("/").toString();
        OkHttpClient testClient = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .build();
        ArtifactoryClient client = new ArtifactoryClient(baseUrl, "user", "pass", null, ps);

        List<String> versions = client.getExistingVersion("org", "pkg");

        Assert.assertNotNull(versions);
        Assert.assertEquals(versions.size(), 3);
        Assert.assertTrue(versions.contains("1.1.0"));
    }

    @Test(timeOut = 5000)
    public void testResolveVersion_softMediumHard() throws Exception {
        final String body = "{\"versions\": [\"1.0.1\", \"1.0.2\", \"1.1.0\", \"2.0.0\"]}";
        Dispatcher dispatcher = new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    ps.println("Dispatcher received request: " + request.getRequestUrl());
                    System.out.println("Dispatcher received request: " + request.getRequestUrl());
                } catch (Exception ignored) {}
                return new MockResponse().setResponseCode(200).setBody(body)
                        .addHeader("Content-Type", "application/json");
            }
        };
        server.setDispatcher(dispatcher);

        String baseUrl = server.url("/").toString();
        OkHttpClient testClient = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .build();
        ArtifactoryClient client = new ArtifactoryClient(baseUrl, "user", "pass", null, ps);

        // soft -> same major (1.x) highest >= requested
        String resolvedSoft = client.resolveVersion("org", "pkg", "1.0.1", "soft");
        Assert.assertEquals(resolvedSoft, "1.1.0");

        // medium -> same major.minor -> 1.0.x highest
        String resolvedMedium = client.resolveVersion("org", "pkg", "1.0.1", "medium");
        Assert.assertEquals(resolvedMedium, "1.0.2");

        // hard -> exact -> expect exception when not present
        try {
            client.resolveVersion("org", "pkg", "1.0.3", "hard");
            Assert.fail("Expected an exception for missing exact version");
        } catch (Exception ex) {
            String msg = ex.getMessage();
            Assert.assertTrue(msg.contains("Exact version requested not found") || msg.contains("not found"));
        }
    }

    // New quick test: resolve should fail when no compatible version exists
    @Test(expectedExceptions = IOException.class, timeOut = 5000)
    public void testResolveVersion_notFound() throws Exception {
        final String body = "{\"versions\": [\"2.0.0\"]}";
        Dispatcher dispatcher = new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(200).setBody(body).addHeader("Content-Type", "application/json");
            }
        };
        server.setDispatcher(dispatcher);

        String baseUrl = server.url("/").toString();
        OkHttpClient testClient = new OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).writeTimeout(2, TimeUnit.SECONDS).build();
        ArtifactoryClient client = new ArtifactoryClient(baseUrl, "user", "pass",null, ps);

        // Request a 1.x range when only 2.x exists -> expect IOException
        client.resolveVersion("org", "pkg", "1.0.0", "soft");
    }

    // New quick test: push should reject when version already exists (equal)
    @Test(expectedExceptions = IOException.class, timeOut = 5000)
    public void testPushPackage_rejectsExistingVersion() throws Exception {
        final String org = "ranvin";
        final String pkg = "hello_world";
        final String version = "1.1.0";
        final String balaName = org + "-" + pkg + "-any-" + version + ".bala";

        Dispatcher dispatcher = new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (path != null && path.endsWith("/versions.json")) {
                    // versions.json contains the same version we will try to push
                    return new MockResponse().setResponseCode(200).setBody("{\"versions\": [\"1.1.0\"]}").addHeader("Content-Type", "application/json");
                }
                return new MockResponse().setResponseCode(404);
            }
        };
        server.setDispatcher(dispatcher);

        String baseUrl = server.url("/").toString();
        OkHttpClient testClient = new OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).writeTimeout(2, TimeUnit.SECONDS).build();
        ArtifactoryClient client = new ArtifactoryClient(baseUrl, "user", "pass", null, ps);

        Path tmp = Files.createTempDirectory("bala-test");
        Path bala = tmp.resolve(balaName);
        Files.write(bala, "FAKE".getBytes());
        try {
            client.pushPackage(bala);
        } finally {
            Files.deleteIfExists(bala);
            Files.deleteIfExists(tmp);
        }
    }
}
