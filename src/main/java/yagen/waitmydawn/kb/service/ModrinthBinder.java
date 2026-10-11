package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.config.NetworkBootstrap;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** JAR 摘要优先确定项目身份；摘要无匹配时使用游戏版本、加载器及完整版本号搜索。 */
public class ModrinthBinder {
    private static final Logger log = LoggerFactory.getLogger(ModrinthBinder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API = "https://api.modrinth.com/v2";
    private final Transport transport;
    private final Map<String, JsonNode> cache = new HashMap<>();
    private long unavailableUntil;

    public record BindResult(String slug, String modrinthUrl, String source) {}
    record Response(int status, String body) {}
    @FunctionalInterface interface Transport { Response get(URI uri) throws IOException, InterruptedException; }

    public ModrinthBinder() { this(defaultTransport()); }
    ModrinthBinder(Transport transport) { this.transport = transport; }

    private static Transport defaultTransport() {
        NetworkBootstrap.configure();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return uri -> {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "yagen/MAA-Knowledge-Builder/1.0").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        };
    }

    public BindResult bind(String modId, String displayName, String modVersion, String mcVersion, String loader) {
        return bind(null, modId, displayName, modVersion, mcVersion, loader);
    }

    public BindResult bind(Path jar, String modId, String displayName,
                           String modVersion, String mcVersion, String loader) {
        if (System.nanoTime() < unavailableUntil) return new BindResult(null, null, "modrinth-unavailable");
        try {
            if (jar != null) {
                JsonNode version = request("/version_file/" + sha512(jar) + "?algorithm=sha512");
                if (version != null) {
                    String projectId = version.path("project_id").asText();
                    if (projectId.isBlank()) throw new IOException("Hash response has no project_id");
                    JsonNode project = request("/project/" + encode(projectId));
                    if (project == null || project.path("slug").asText().isBlank())
                        throw new IOException("Hash-matched project could not be resolved");
                    return bound(project.path("slug").asText(), modId, "JAR sha512");
                }
            }
            var queries = new LinkedHashSet<String>();
            if (modId != null && !modId.isBlank()) queries.add(modId);
            if (displayName != null && !displayName.isBlank()) queries.add(displayName);
            if (modId != null && modId.contains("_")) queries.add(modId.replace('_', '-'));
            for (String query : queries) {
                String slug = searchAndMatch(query, modVersion, mcVersion, loader);
                if (slug != null) return bound(slug, modId, "version match");
            }
            // 完成搜索仍不能证明项目从未发布，只能确认本次未找到精确匹配。
            log.info("No exact Modrinth match for {} (version={}, Minecraft={}, loader={})", modId, modVersion, mcVersion, loader);
            return new BindResult(null, null, "modrinth-no-match");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Modrinth binding interrupted");
        } catch (IOException e) {
            unavailableUntil = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            log.warn("Modrinth lookup unavailable for {}: {}. Remaining scan lookups pause for 30s; retry scanning after checking network/address policy", modId, e.toString());
            return new BindResult(null, null, "modrinth-unavailable");
        }
    }

    /** 临时网络故障不应抹掉同一模组身份已经确认的绑定。 */
    public static void preserveKnownBinding(yagen.waitmydawn.kb.model.ModEntry entry,
                                            yagen.waitmydawn.kb.model.ModEntry previous) {
        if (!"modrinth-unavailable".equals(entry.getSource()) || previous == null || previous.getSlug() == null) return;
        if (!Objects.equals(entry.getLoader(), previous.getLoader())
                || !Objects.equals(entry.getMcVersion(), previous.getMcVersion())) return;
        entry.setSlug(previous.getSlug());
        entry.setModrinthUrl(previous.getModrinthUrl());
    }

    private BindResult bound(String slug, String modId, String method) {
        log.info("Slug bound ({}): {} -> {}", method, modId, slug);
        return new BindResult(slug, "https://modrinth.com/mod/" + slug, "modrinth");
    }

    private String searchAndMatch(String query, String modVersion, String mcVersion, String loader)
            throws IOException, InterruptedException {
        var facets = new ArrayList<List<String>>();
        facets.add(List.of("project_type:mod"));
        if (loader != null && !loader.isBlank()) facets.add(List.of("categories:" + loader));
        if (mcVersion != null && !mcVersion.isBlank()) facets.add(List.of("versions:" + mcVersion));
        JsonNode search = request("/search?query=" + encode(query) + "&facets=" + encode(MAPPER.writeValueAsString(facets)) + "&limit=5");
        if (search == null || !search.path("hits").isArray()) throw new IOException("Invalid search response");
        for (JsonNode hit : search.path("hits")) {
            String slug = hit.path("slug").asText();
            String project = hit.path("project_id").asText(slug);
            if (slug.isBlank() || project.isBlank()) continue;
            String filters = "?loaders=" + encode(MAPPER.writeValueAsString(loader == null || loader.isBlank() ? List.of() : List.of(loader)))
                    + "&game_versions=" + encode(MAPPER.writeValueAsString(mcVersion == null || mcVersion.isBlank() ? List.of() : List.of(mcVersion)));
            JsonNode versions = request("/project/" + encode(project) + "/version" + filters);
            if (versions == null || !versions.isArray()) throw new IOException("Invalid project version response");
            for (JsonNode version : versions) {
                if (modVersion != null && modVersion.equals(version.path("version_number").asText())) return slug;
            }
        }
        return null;
    }

    private JsonNode request(String path) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        JsonNode existing = cache.get(path);
        if (existing != null) return existing;
        Response response = transport.get(URI.create(API + path));
        if (response.status() == 404) return null;
        if (response.status() != 200) throw new IOException("Modrinth HTTP " + response.status());
        JsonNode result = MAPPER.readTree(response.body());
        if (result == null) throw new IOException("Empty Modrinth response");
        cache.put(path, result);
        return result;
    }

    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private static String sha512(Path jar) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-512");
            try (var input = Files.newInputStream(jar)) {
                byte[] bytes = new byte[65536]; int count;
                while ((count = input.read(bytes)) >= 0) digest.update(bytes, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
