package de.livemap;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publishes files to a branch through the GitHub Git Data API. Every publish creates a single
 * parent-less commit on top of the current tree, so the repository history does not grow.
 */
final class GitHubPublisher {
    private static final Pattern FIRST_SHA = Pattern.compile("\"sha\"\\s*:\\s*\"([0-9a-f]{40})\"");
    private static final Pattern TREE_SHA = Pattern.compile("\"tree\"\\s*:\\s*\\{\\s*\"sha\"\\s*:\\s*\"([0-9a-f]{40})\"");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String token;
    private final String api;
    private final String branch;

    GitHubPublisher(String token, String repo, String branch) {
        this.token = token;
        this.api = "https://api.github.com/repos/" + repo;
        this.branch = branch;
    }

    void publish(Map<String, byte[]> files) throws IOException, InterruptedException {
        String headSha = extract(FIRST_SHA, call("GET", "/git/ref/heads/" + branch, null));
        String baseTree = extract(TREE_SHA, call("GET", "/git/commits/" + headSha, null));

        StringBuilder entries = new StringBuilder();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            String body = "{\"content\":\"" + Base64.getEncoder().encodeToString(f.getValue())
                    + "\",\"encoding\":\"base64\"}";
            String blob = extract(FIRST_SHA, call("POST", "/git/blobs", body));
            if (entries.length() > 0) entries.append(',');
            entries.append("{\"path\":\"").append(f.getKey())
                    .append("\",\"mode\":\"100644\",\"type\":\"blob\",\"sha\":\"").append(blob).append("\"}");
        }
        String tree = extract(FIRST_SHA, call("POST", "/git/trees",
                "{\"base_tree\":\"" + baseTree + "\",\"tree\":[" + entries + "]}"));
        String commit = extract(FIRST_SHA, call("POST", "/git/commits",
                "{\"message\":\"Update map\",\"tree\":\"" + tree + "\",\"parents\":[]}"));
        call("PATCH", "/git/refs/heads/" + branch, "{\"sha\":\"" + commit + "\",\"force\":true}");
    }

    private String call(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "LiveMap-Plugin");
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            throw new IOException(method + " " + path + " -> HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    private static String extract(Pattern p, String json) throws IOException {
        Matcher m = p.matcher(json);
        if (!m.find()) throw new IOException("Unexpected GitHub response: " + json);
        return m.group(1);
    }
}
