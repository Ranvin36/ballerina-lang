package org.ballerinalang.artifactory;

import java.io.IOException;
import java.util.Set;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class RetryInterceptor implements Interceptor {
    private static final Set<String> RETRIABLE_METHODS =
            Set.of("GET","HEAD","PUT","POST");

    private final int maxRetries;
    private final long baseDelayMs;

    public RetryInterceptor(int maxRetries, long baseDelayMs) {
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
    }

    // Implements the retry logic for retriable HTTP methods and status codes.
    @Override
    public okhttp3.Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (!RETRIABLE_METHODS.contains(request.method())) {
            return chain.proceed(request);
        }

        int attempt = 0;
        while (true) {
            try {
                Response response = chain.proceed(request);
                int code = response.code();
                if (code == 502 || code == 503 || code == 504) {
                    if (attempt < maxRetries) {
                        closeQuietly(response);
                        sleep(backoffMs(++attempt));
                        continue;
                    }
                }
                return response;
            } catch (IOException ioe) {
                if (attempt < maxRetries) {
                    sleep(backoffMs(++attempt));
                    continue;
                }
                throw ioe;
            }
        }
    }

    // Calculates backoff duration based on attempt number.
    private long backoffMs(int attempt) {
        return baseDelayMs * (1L << (attempt - 1));
    }

    // Sleeps for the specified backoff duration.
    private static void sleep(long backoffMs) {
        try {
            Thread.sleep(backoffMs);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // Closes the response body to prevent resource leaks (connections, memory).
    private void closeQuietly(Response response) {
        if(response.body() != null) {
            try{
                response.body().close();
            }
            catch (Exception e) {}
        }
    }
}