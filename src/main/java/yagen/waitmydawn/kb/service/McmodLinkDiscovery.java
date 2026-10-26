package yagen.waitmydawn.kb.service;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** 只从实际链接发现分类与教程，不构造未出现的地址。 */
final class McmodLinkDiscovery {
    private McmodLinkDiscovery() {}
    record Category(int id, String name, String url) {}

    static List<Category> categories(Document document, String classId, String baseUrl) {
        var pattern = Pattern.compile("^/item/list/" + Pattern.quote(classId) + "-([1-9][0-9]*)\\.html$");
        var categories = new LinkedHashMap<Integer, Category>();
        for (Element a : document.select("a[href]")) {
            if (a.closest(".comment, .comments, .common-comment-block") != null) continue;
            String url = mcmodUrl(baseUrl, a.attr("href"));
            if (url == null) continue;
            var match = pattern.matcher(URI.create(url).getPath());
            if (!match.matches()) continue;
            try {
                int id = Integer.parseInt(match.group(1));
                var known = WikiScraperService.McmodCategory.fromId(id);
                String name = known == null ? a.text().strip() : known.getName();
                categories.putIfAbsent(id, new Category(id, name.isBlank() ? "分类 " + id : name, url));
            } catch (NumberFormatException ignored) { }
        }
        return List.copyOf(categories.values());
    }

    static Map<String, String> tutorials(Document doc, String baseUrl) {
        var regions = doc.select(".post-block, .class-post-list");
        if (regions.isEmpty()) regions = doc.select(".class-text");
        var links = new LinkedHashMap<String, String>();
        for (Element region : regions) for (Element a : region.select("a[href]")) {
            if (a.closest(".comment, .comments, .common-comment-block, .recommend, .related-posts") != null) continue;
            String url = mcmodUrl(baseUrl, a.attr("href"));
            if (url != null && URI.create(url).getPath().matches("/post/[1-9][0-9]*\\.html")) {
                String title = a.text().strip();
                if (title.isEmpty() && a.selectFirst("img[alt]") != null) title = a.selectFirst("img[alt]").attr("alt");
                if (!links.containsKey(url) || links.get(url).isBlank()) links.put(url, title);
            }
        }
        return links;
    }

    static List<String> tutorialPagination(Document doc, String baseUrl) {
        var links = new LinkedHashSet<String>();
        for (Element a : doc.select(".class-post-list .pagination a[href], .class-post .pagination a[href], .class-post-pages a[href]")) {
            String url = mcmodUrl(baseUrl, a.attr("href"));
            if (url != null && URI.create(url).getPath().equals(URI.create(baseUrl).getPath())) links.add(url);
        }
        return List.copyOf(links);
    }

    static String mcmodUrl(String base, String href) {
        try {
            URI resolved = URI.create(base).resolve(href.strip());
            String host = resolved.getHost();
            if (host == null || !(host.equalsIgnoreCase("www.mcmod.cn") || host.equalsIgnoreCase("mcmod.cn"))) return null;
            String url = WebUrlPolicy.normalize(resolved.toString());
            URI uri = URI.create(url);
            return "https://www.mcmod.cn" + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (IllegalArgumentException e) { return null; }
    }
}
