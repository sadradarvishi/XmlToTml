package com.example.XML2TML.service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
public class XmlFetchService {

    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private static final Pattern VALID_URL =
            Pattern.compile("^http?://[^\\s?#]+/\\d{6}\\.xml$", Pattern.CASE_INSENSITIVE);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static boolean isValidUrl(String url) {
        return url != null && VALID_URL.matcher(url.trim()).matches();
    }

    public byte[] fetch(String url) throws Exception {
        if (!isValidUrl(url)) {
            throw new IllegalArgumentException("Invalid URL");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IllegalArgumentException(
                        "Server returned HTTP " + response.statusCode() + " for " + url);
            }
            return readLimited(body);
        }
    }

    private byte[] readLimited(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > MAX_BYTES) {
                throw new IllegalArgumentException("XML is larger than 2 MB.");
            }
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }
}
