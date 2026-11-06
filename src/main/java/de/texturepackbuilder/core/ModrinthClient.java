package de.texturepackbuilder.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.texturepackbuilder.TexturePackBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Sucht und lädt Resource Packs über die offizielle Modrinth-API (api.modrinth.com/v2). */
public final class ModrinthClient {
    private static final String API = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "texturepackbuilder/1.0 (Minecraft Fabric mod)";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public record Hit(String id, String slug, String title, String author, long downloads,
                      String description, String iconUrl) {}

    public record Page(List<Hit> hits, int total) {}

    /** Sortierungen der Modrinth-API. */
    public static final String[] SORTS = {"relevance", "downloads", "newest", "follows"};
    /** Auflösungs-Kategorien für Resource Packs auf Modrinth (null = alle). */
    public static final String[] RESOLUTIONS = {null, "8x-", "16x", "32x", "48x", "64x", "128x", "256x", "512x+"};

    private ModrinthClient() {}

    public static String fileStem(String slug) {
        return slug.replaceAll("[^A-Za-z0-9._+-]", "_") + "-";
    }

    /** Wurde von diesem Projekt schon eine Datei heruntergeladen? */
    public static boolean isDownloaded(String slug, java.util.Set<String> downloadedFiles) {
        String stem = fileStem(slug);
        for (String f : downloadedFiles) if (f.startsWith(stem) && f.endsWith(".zip")) return true;
        return false;
    }

    public static java.util.Set<String> listDownloads() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (java.util.stream.Stream<Path> s = Files.list(Dirs.downloads())) {
            s.forEach(p -> out.add(p.getFileName().toString()));
        } catch (IOException ignored) {
        }
        return out;
    }

    public static CompletableFuture<byte[]> fetchBytes(String url) {
        return HTTP.sendAsync(req(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).thenApply(r -> {
            if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode());
            return r.body();
        });
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static HttpRequest.Builder req(String url) {
        return HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(60));
    }

    private static CompletableFuture<String> getString(String url) {
        return HTTP.sendAsync(req(url).GET().build(), HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() != 200) throw new IllegalStateException("Modrinth antwortet mit HTTP " + r.statusCode());
            return r.body();
        });
    }

    public static CompletableFuture<Page> search(String query, String sort, String resolution, int offset, int limit) {
        StringBuilder facets = new StringBuilder("[[\"project_type:resourcepack\"],[\"versions:")
                .append(TexturePackBuilder.GAME_VERSION).append("\"]");
        if (resolution != null) facets.append(",[\"categories:").append(resolution).append("\"]");
        facets.append(']');
        String url = API + "/search?limit=" + limit + "&offset=" + offset + "&index=" + sort
                + "&query=" + enc(query.trim()) + "&facets=" + enc(facets.toString());
        return getString(url).thenApply(body -> {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            List<Hit> hits = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("hits")) {
                JsonObject o = el.getAsJsonObject();
                hits.add(new Hit(o.get("project_id").getAsString(), o.get("slug").getAsString(),
                        o.get("title").getAsString(),
                        str(o, "author", "?"),
                        o.has("downloads") ? o.get("downloads").getAsLong() : 0,
                        str(o, "description", ""),
                        str(o, "icon_url", null)));
            }
            int total = root.has("total_hits") ? root.get("total_hits").getAsInt() : hits.size();
            return new Page(hits, total);
        });
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    /** Lädt die neueste zur Spielversion passende Version eines Projekts in den Download-Ordner. */
    public static CompletableFuture<Path> download(Hit hit) {
        return download(hit, Long.MAX_VALUE);
    }

    /** Wie download(hit), aber Dateien über maxBytes werden abgelehnt (für den automatischen PvP-Download). */
    public static CompletableFuture<Path> download(Hit hit, long maxBytes) {
        String url = API + "/project/" + hit.id() + "/version?game_versions="
                + enc("[\"" + TexturePackBuilder.GAME_VERSION + "\"]");
        return getString(url).thenCompose(body -> {
            JsonArray versions = JsonParser.parseString(body).getAsJsonArray();
            if (versions.isEmpty()) throw new IllegalStateException("Keine Version für " + TexturePackBuilder.GAME_VERSION);
            JsonObject version = versions.get(0).getAsJsonObject();
            JsonObject file = pickFile(version.getAsJsonArray("files"));
            if (file.has("size") && file.get("size").getAsLong() > maxBytes) {
                throw new IllegalStateException("too large: " + (file.get("size").getAsLong() / 1_000_000) + " MB");
            }
            String fileUrl = file.get("url").getAsString();
            String sha1 = file.getAsJsonObject("hashes").has("sha1")
                    ? file.getAsJsonObject("hashes").get("sha1").getAsString() : null;

            String name = fileStem(hit.slug())
                    + version.get("version_number").getAsString().replaceAll("[^A-Za-z0-9._+-]", "_") + ".zip";
            Path target = Dirs.downloads().resolve(name);
            if (Files.exists(target)) return CompletableFuture.completedFuture(target);
            Path tmp = target.resolveSibling(name + ".part");
            try {
                Files.createDirectories(target.getParent());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return HTTP.sendAsync(req(fileUrl).GET().build(), HttpResponse.BodyHandlers.ofFile(tmp)).thenApply(r -> {
                try {
                    if (r.statusCode() != 200) throw new IOException("Download fehlgeschlagen: HTTP " + r.statusCode());
                    if (sha1 != null && !sha1.equalsIgnoreCase(sha1(tmp))) throw new IOException("Prüfsumme stimmt nicht");
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    return target;
                } catch (IOException e) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignored) {
                    }
                    throw new UncheckedIOException(e);
                }
            });
        });
    }

    private static JsonObject pickFile(JsonArray files) {
        JsonObject fallback = null;
        for (JsonElement el : files) {
            JsonObject f = el.getAsJsonObject();
            if (f.has("primary") && f.get("primary").getAsBoolean()) return f;
            if (fallback == null && f.get("filename").getAsString().toLowerCase().endsWith(".zip")) fallback = f;
        }
        if (fallback != null) return fallback;
        if (files.isEmpty()) throw new IllegalStateException("Version hat keine Dateien");
        return files.get(0).getAsJsonObject();
    }

    private static String sha1(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(p)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
