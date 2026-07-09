package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 通过 Modrinth API v2 精确绑定 slug。
 *
 * 流程:
 *   1. 用 mcVersion + loader 筛选搜索 modId → 拿到候选列表
 *   2. 对每个候选拉取版本列表 → 用 JAR 解析出的 modVersion 精确匹配
 *   3. 若 modId 搜索匹配失败 → 改用 displayName 搜索并重复版本匹配
 *   4. 都不匹配 → source=non-modrinth
 */
public class ModrinthBinder {

    private static final Logger log = LoggerFactory.getLogger(ModrinthBinder.class);
    private static final String MODRINTH_API = "https://api.modrinth.com/v2";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String UA = "MAA-Knowledge-Builder/1.0 (yagen)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    // Modrinth loader → category facet value
    private static final Map<String, String> LOADER_CATEGORY = Map.of(
            "neoforge", "neoforge",
            "forge", "forge",
            "fabric", "fabric",
            "quilt", "quilt",
            "vanilla", "datapack"   // vanilla mods are usually datapacks on Modrinth
    );

    public record BindResult(String slug, String modrinthUrl, String source) {}

    // ==================== Public API ====================

    /**
     * Bind a slug to the given mod metadata.
     *
     * @param modId       internal modId from JAR metadata
     * @param displayName human-readable display name
     * @param modVersion  mod version string from JAR metadata (e.g. "2.0-beta-17")
     * @param mcVersion   MC version inferred from dependencies (e.g. "1.21.1")
     * @param loader      loader name (e.g. "neoforge")
     * @return BindResult with slug + URL + source, or null if not found
     */
    public BindResult bind(String modId, String displayName,
                           String modVersion, String mcVersion, String loader) {
        // --- Step 1: search by modId with version+loader filter ---
        BindResult result = searchAndMatch(modId, modVersion, mcVersion, loader);
        if (result != null) {
            log.info("Slug bound (modId): {} to {}", modId, result.slug());
            return result;
        }

        // --- Step 2: search by displayName (if different from modId) ---
        if (displayName != null && !displayName.isBlank()
                && !displayName.equalsIgnoreCase(modId)) {
            result = searchAndMatch(displayName, modVersion, mcVersion, loader);
            if (result != null) {
                log.info("Slug bound (displayName): {} to {}", displayName, result.slug());
                return result;
            }
        }

        // --- Step 3: try with underscore→dash conversion ---
        if (modId.contains("_")) {
            String altId = modId.replace('_', '-');
            result = searchAndMatch(altId, modVersion, mcVersion, loader);
            if (result != null) {
                log.info("Slug bound (altId): {} → {}", altId, result.slug());
                return result;
            }
        }

        // --- Step 4: not found on Modrinth ---
        log.info("Slug binding failed for {} (v{}) — not on Modrinth", modId, modVersion);
        return new BindResult(null, null, "non-modrinth");
    }

    // ==================== Core logic ====================

    /**
     * Search Modrinth with version+loader filter, then match by exact mod version.
     */
    private BindResult searchAndMatch(String query, String modVersion,
                                      String mcVersion, String loader) {
        List<SearchHit> hits = searchModrinth(query, mcVersion, loader);
        if (hits.isEmpty()) return null;

        // Try to match each candidate's version list against our modVersion
        for (int i = 0; i < Math.min(hits.size(), 5); i++) {
            SearchHit hit = hits.get(i);
            List<String> versions = fetchProjectVersions(hit.slug());
            boolean matched = versions.stream()
                    .anyMatch(v -> v.equals(modVersion) || v.contains(modVersion));
            if (matched) {
                return new BindResult(hit.slug(),
                        "https://modrinth.com/mod/" + hit.slug(),
                        "modrinth");
            }
            log.debug("  Version mismatch: {} has versions [{}...], looking for {}",
                    hit.slug(),
                    versions.isEmpty() ? "none" : String.join(", ", versions.subList(0, Math.min(3, versions.size()))),
                    modVersion);
        }

        return null;
    }

    // ==================== Modrinth API calls ====================

    /**
     * Search Modrinth with version + loader facets.
     *
     * Facet format: [[\"categories:{loader}\"],[\"versions:{mcVersion}\"]]
     * Inner array = OR, outer = AND
     */
    private List<SearchHit> searchModrinth(String query, String mcVersion, String loader) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

            // Build facets: filter by loader category AND game version
            String cat = LOADER_CATEGORY.getOrDefault(loader, loader);
            String facets;
            if (mcVersion != null && !mcVersion.isBlank()) {
                facets = "[[\"categories:" + cat + "\"],[\"versions:" + mcVersion + "\"]]";
            } else {
                facets = "[[\"categories:" + cat + "\"]]";
            }

            String encodedFacets = URLEncoder.encode(facets, StandardCharsets.UTF_8);
            // Also add project_type:mod facet
            String encodedType = URLEncoder.encode("[[\"project_type:mod\"]]", StandardCharsets.UTF_8);

            String url = MODRINTH_API + "/search?query=" + encodedQuery
                    + "&facets=" + encodedFacets + "&limit=5";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", UA)
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return parseHits(mapper.readTree(resp.body()));
            } else {
                log.warn("Modrinth search HTTP {} for '{}'", resp.statusCode(), query);
            }
        } catch (Exception e) {
            log.warn("Modrinth search failed for '{}': {}", query, e.getMessage());
        }
        return Collections.emptyList();
    }

    /** Fetch all version numbers for a project from Modrinth API */
    private List<String> fetchProjectVersions(String slug) {
        List<String> versions = new ArrayList<>();
        try {
            // Use the project endpoint to get version IDs, then resolve each
            // Actually, we can use /project/{slug}/version to get version list directly
            String url = MODRINTH_API + "/project/" + slug + "/version";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", UA)
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode arr = mapper.readTree(resp.body());
                if (arr.isArray()) {
                    for (JsonNode v : arr) {
                        String versionNumber = v.path("version_number").asText(null);
                        if (versionNumber != null) {
                            versions.add(versionNumber);
                        }
                    }
                }
            } else {
                log.debug("Modrinth version fetch HTTP {} for slug={}", resp.statusCode(), slug);
            }
        } catch (Exception e) {
            log.debug("Modrinth version fetch failed for {}: {}", slug, e.getMessage());
        }
        return versions;
    }

    // ==================== JSON parsing ====================

    private List<SearchHit> parseHits(JsonNode root) {
        List<SearchHit> hits = new ArrayList<>();
        JsonNode arr = root.path("hits");
        if (arr.isArray()) {
            for (JsonNode h : arr) {
                String slug = h.path("slug").asText("");
                if (!slug.isEmpty()) {
                    hits.add(new SearchHit(
                            slug,
                            h.path("title").asText(""),
                            h.path("description").asText(""),
                            h.path("downloads").asInt(0)));
                }
            }
        }
        return hits;
    }

    private record SearchHit(String slug, String title, String description, int downloads) {}
}
