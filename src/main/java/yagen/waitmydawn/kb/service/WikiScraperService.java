package yagen.waitmydawn.kb.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 外部知识爬取: MC Wiki (中文) + MC百科。
 */
public class WikiScraperService {

    private static final Logger log = LoggerFactory.getLogger(WikiScraperService.class);

    // Browser-mimicking headers to avoid 403 from mcmod.cn
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final String ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8";
    private static final String ACCEPT_LANG = "zh-CN,zh;q=0.9,en;q=0.8";
    private static final String ACCEPT_ENC = "gzip, deflate, br";
    private static final int CATEGORY_DELAY_MS = 1000;      // between categories
    private static final int RETRY_BASE_DELAY_MS = 1000;     // base retry delay

    // ==================== MC百科 分类枚举 ====================

    public enum McmodCategory {
        ITEM(1, "物品/方块"),
        BIOME(2, "群系/群落"),
        DIM(3, "世界/维度"),
        ENTITY(4, "生物/实体"),
        ENCHANT(5, "附魔/魔咒"),
        EFFECT(6, "BUFF/DEBUFF"),
        MUL_BLOCK(7, "多方块结构"),
        STRUCTURE(8, "自然生成"),
        HOTKEYS(9, "绑定热键"),
        GAME_SETTINGS(10, "游戏设定"),
        SPELL(229, "法术");

        private final int id;
        private final String name;

        McmodCategory(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public static McmodCategory fromId(int id) {
            for (McmodCategory c : values()) {
                if (c.id == id) return c;
            }
            return null;
        }
    }

    // ==================== MC Wiki (中文) ====================

    /**
     * Baseline MC Wiki pages — 中文基础页面
     */
    public static final String[] BASELINE_PAGES = {
            "合成", "烧炼", "酿造", "附魔", "锻造",
            "交易", "红石", "命令", "材质",
            "物品", "方块", "生物", "生物群系", "状态效果", "结构", "教程"
    };

    /**
     * Fetch a single MC Wiki page (中文)
     */
    public WebPage fetchMcWiki(String pageName) {
        String url = "https://zh.minecraft.wiki/w/" + pageName.replace(' ', '_');
        return fetchPage(url, "mcwiki");
    }

    /**
     * Fetch a list of MC Wiki pages
     */
    public List<WebPage> fetchMcWikiPages(List<String> pageNames) {
        List<WebPage> pages = new ArrayList<>();
        for (String name : pageNames) {
            WebPage p = fetchMcWiki(name);
            if (p != null) {
                pages.add(p);
                log.info("MC Wiki: {}", name);
            }
        }
        return pages;
    }

    // ==================== MC百科 ====================

    /**
     * 提取 namespace 的正则: modid:item_name
     */
    private static final Pattern NAMESPACE_PATTERN =
            Pattern.compile("([a-z_][a-z0-9_]*:[a-z_][a-z0-9_]*)");

    @FunctionalInterface
    public interface ScrapeProgress {
        /**
         * @param itemCur  0=category start, -1=done, >0=current item index
         * @param status   "ok" / "empty" / "blocked" / "error" / "404"
         */
        void onProgress(int catCur, int catTotal, String categoryName,
                        int itemCur, int itemTotal, String status);
    }

    /** Regex for real item links: /item/12345.html */
    private static final Pattern ITEM_LINK_PATTERN = Pattern.compile("^/item/\\d+\\.html$");

    /** Minimum body length to consider a page as "loaded" (not a block/error page) */
    private static final int MIN_BODY_LEN = 800;

    /** Result of fetching a category page */
    private record CategoryFetchResult(Document doc, int httpStatus, int bodyLen, int retries) {}

    /**
     * Deep crawl MC百科 by modId — 遍历所有分类，提取子网页的命名空间映射。
     * 返回: 主页面 (携带 subWebPage 映射) + 各分类页面。
     */
    public List<WebPage> scrapeModDeep(String modId) {
        return scrapeModDeep(modId, null);
    }

    public List<WebPage> scrapeModDeep(String modId, ScrapeProgress progress) {
        List<WebPage> pages = new ArrayList<>();
        String classId = findModClassId(modId);
        if (classId == null) return pages;

        // Visit main page first to get session cookies
        String mainUrl = "https://www.mcmod.cn/class/" + classId + ".html";
        Map<String, String> sessionCookies = new LinkedHashMap<>();
        WebPage main = fetchPageWithCookies(mainUrl, "mcmod", sessionCookies);
        if (main != null) pages.add(main);

        // 遍历所有分类，收集 subWebPage 映射
        Map<String, String> subWebPage = new LinkedHashMap<>();
        McmodCategory[] allCats = McmodCategory.values();
        int catIdx = 0;

        for (McmodCategory cat : allCats) {
            catIdx++;
            String subUrl = "https://www.mcmod.cn/item/list/" + classId + "-" + cat.getId() + ".html";

            // Fetch with retry + cookies + browser headers
            CategoryFetchResult fetch = fetchCategoryWithRetry(subUrl, 3, sessionCookies, mainUrl);
            if (fetch.doc() == null) {
                String reason = fetch.httpStatus() > 0
                        ? "HTTP " + fetch.httpStatus() : "connection failed";
                log.warn("mcmod.cn {} [{}] — {} ({} retries)", modId, cat.getName(), reason, fetch.retries());
                if (progress != null)
                    progress.onProgress(catIdx, allCats.length, cat.getName(), -1, 0, "error:" + reason);
                continue;
            }

            Document doc = fetch.doc();
            String title = doc.title();

            // 404 check
            if (title.contains("404") || title.contains("未找到")) {
                if (progress != null)
                    progress.onProgress(catIdx, allCats.length, cat.getName(), -1, 0, "404");
                continue;
            }

            // 提取真实物品链接
            Elements itemLinks = doc.select("a[href*=/item/]");
            List<Element> itemLinksP = itemLinks.stream()
                    .filter(el -> ITEM_LINK_PATTERN.matcher(el.attr("href")).matches())
                    .toList();
            int subCount = itemLinksP.size();

            // Determine status for reporting
            String status;
            if (subCount > 0) {
                status = "ok";
            } else if (fetch.bodyLen() < MIN_BODY_LEN) {
                status = "blocked";  // short body + no items = likely rate-limited or JS-only
                log.warn("mcmod.cn {} [{}] — body too short ({} chars), possible block",
                        modId, cat.getName(), fetch.bodyLen());
            } else {
                status = "empty";    // normal body but no items = genuinely empty
            }

            if (progress != null)
                progress.onProgress(catIdx, allCats.length, cat.getName(), 0, subCount, status);

            StringBuilder content = new StringBuilder();
            content.append("MC百科 ").append(cat.getName()).append(": ").append(title).append("\n");

            // 子类别标题
            Elements leftHeaders = doc.select("th.item-list-type-left");
            for (Element th : leftHeaders) {
                String subCat = th.text().trim();
                if (!subCat.isEmpty()) content.append("  [").append(subCat).append("]\n");
            }

            // Build subWebPage as cn(en)→URL directly from <a> attributes (NO item page visits!)
            int itemIdx = 0;
            for (Element link : itemLinksP) {
                itemIdx++;
                String cnName = link.attr("data-cn");
                if (cnName.isEmpty()) cnName = link.text().trim();
                String enName = link.attr("data-en");
                String href = link.attr("href");
                if (cnName.isEmpty() || href.isEmpty()) continue;

                if (!href.startsWith("http")) href = "https://www.mcmod.cn" + href;

                // Key: "秘银矿石(Mithril Ore)" or "幽灵(Ghost)"
                String key = enName.isEmpty() ? cnName : cnName + "(" + enName + ")";
                if (!subWebPage.containsKey(key)) {
                    subWebPage.put(key, href);
                }

                content.append(cnName);
                if (!enName.isEmpty()) content.append(" (").append(enName).append(")");
                content.append("\n");

                // NO item page visit — just report count
                if (progress != null)
                    progress.onProgress(catIdx, allCats.length, cat.getName(), itemIdx, subCount, status);
            }

            // 右侧单元格物品文本
            Elements rightCells = doc.select("td.item-list-type-right");
            for (Element td : rightCells) {
                Elements links = td.select("a[href*=/item/]");
                for (Element a : links) {
                    String text = a.text().trim();
                    if (!text.isEmpty() && content.indexOf(text) < 0) {
                        content.append(text).append("\n");
                    }
                }
            }

            if (progress != null)
                progress.onProgress(catIdx, allCats.length, cat.getName(), -1, subCount, status);

            pages.add(new WebPage(title, subUrl, content.toString(), "mcmod",
                    new LinkedHashMap<>()));

            try { Thread.sleep(CATEGORY_DELAY_MS); } catch (Exception ignored) {}
        }

        // 把 subWebPage 映射挂到第一个页面 (mod 主页面) 上
        if (!pages.isEmpty() && !subWebPage.isEmpty()) {
            WebPage first = pages.get(0);
            pages.set(0, new WebPage(first.title(), first.url(), first.content(), first.source(), subWebPage));
        }

        log.info("mcmod.cn deep: {} pages, {} sub-pages for modId={}", pages.size(), subWebPage.size(), modId);
        return pages;
    }

    /** Legacy: old extractNamespace without cookies (kept for callers that don't have session) */
    private String extractNamespace(String itemUrl, String fallbackName) {
        return extractNamespaceWithCookies(itemUrl, fallbackName, Collections.emptyMap());
    }

    /**
     * Fetch a single MC百科 item page (for incremental Q&A)
     */
    public WebPage fetchMcmodPage(String url) {
        return fetchPage(url, "mcmod");
    }

    /**
     * Fetch a category page with retry, browser headers, cookies, and exponential backoff.
     */
    private CategoryFetchResult fetchCategoryWithRetry(String url, int maxRetries,
                                                        Map<String, String> cookies, String referer) {
        int lastHttpStatus = 0;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                var conn = Jsoup.connect(url)
                        .userAgent(UA)
                        .header("Accept", ACCEPT)
                        .header("Accept-Language", ACCEPT_LANG)
                        .header("Accept-Encoding", ACCEPT_ENC)
                        .header("Referer", referer != null ? referer : "https://www.mcmod.cn/")
                        .header("Connection", "keep-alive")
                        .header("Cache-Control", "max-age=0")
                        .cookies(cookies)
                        .timeout(15000)
                        .ignoreHttpErrors(true)
                        .execute();

                int status = conn.statusCode();

                // Capture any new cookies
                Map<String, String> newCookies = conn.cookies();
                if (!newCookies.isEmpty()) cookies.putAll(newCookies);

                String body = conn.body();
                int bodyLen = body != null ? body.length() : 0;

                if (status == 200 && bodyLen > 200) {
                    return new CategoryFetchResult(conn.parse(), status, bodyLen, attempt);
                }

                lastHttpStatus = status;

                // 403/429 → exponential backoff: 2s, 4s, 8s
                if (status == 403 || status == 429) {
                    long delay = RETRY_BASE_DELAY_MS * (1L << attempt);
                    log.debug("HTTP {} on attempt {}/{} — backing off {}ms", status, attempt + 1, maxRetries, delay);
                    try { Thread.sleep(delay); } catch (Exception ignored) {}
                } else if (status == 404) {
                    break; // permanent, don't retry
                } else {
                    try { Thread.sleep(RETRY_BASE_DELAY_MS); } catch (Exception ignored) {}
                }
            } catch (java.net.SocketTimeoutException e) {
                lastHttpStatus = 0;
                try { Thread.sleep(RETRY_BASE_DELAY_MS * (attempt + 1)); } catch (Exception ignored) {}
            } catch (Exception e) {
                lastHttpStatus = 0;
                try { Thread.sleep(RETRY_BASE_DELAY_MS); } catch (Exception ignored) {}
            }
        }
        return new CategoryFetchResult(null, lastHttpStatus, 0, maxRetries);
    }

    /** Fetch a page and capture cookies from the response */
    private WebPage fetchPageWithCookies(String url, String source, Map<String, String> cookies) {
        try {
            var conn = Jsoup.connect(url)
                    .userAgent(UA)
                    .header("Accept", ACCEPT)
                    .header("Accept-Language", ACCEPT_LANG)
                    .header("Accept-Encoding", ACCEPT_ENC)
                    .timeout(15000)
                    .execute();

            // Capture cookies for session reuse
            Map<String, String> newCookies = conn.cookies();
            if (!newCookies.isEmpty()) cookies.putAll(newCookies);

            Document doc = conn.parse();
            StringBuilder content = new StringBuilder();
            content.append("Source: ").append(doc.title()).append("\nURL: ").append(url).append("\n\n");

            Elements paras = doc.select("p, li, h2, h3, h4, td, .mw-parser-output > *");
            for (Element e : paras) {
                String text = e.text().trim();
                if (text.isEmpty()) continue;
                String tag = e.tagName();
                if (tag.startsWith("h")) content.append("\n## ").append(text).append("\n\n");
                else content.append(text).append("\n");
            }
            if (content.length() == ("Source: " + doc.title() + "\nURL: " + url + "\n\n").length()) {
                content.append(doc.body().text());
            }
            return new WebPage(doc.title(), url, content.toString(), source, new LinkedHashMap<>());
        } catch (Exception e) {
            log.debug("Fetch failed {}: {}", url, e.getMessage());
        }
        return null;
    }

    /** Extract namespace from an item page, using session cookies */
    private String extractNamespaceWithCookies(String itemUrl, String fallbackName,
                                                Map<String, String> cookies) {
        try {
            Document doc = Jsoup.connect(itemUrl)
                    .userAgent(UA)
                    .header("Accept", ACCEPT)
                    .header("Accept-Language", ACCEPT_LANG)
                    .header("Accept-Encoding", ACCEPT_ENC)
                    .header("Referer", "https://www.mcmod.cn/")
                    .cookies(cookies)
                    .timeout(12000)
                    .get();

            Element giveElem = doc.selectFirst(".item-give.mc-hover-hand");
            if (giveElem != null) {
                String command = giveElem.attr("data-command");
                if (command != null && !command.isEmpty()) {
                    java.util.regex.Matcher m = NAMESPACE_PATTERN.matcher(command);
                    if (m.find()) return m.group(1);
                }
            }
            String title = doc.title();
            if (title != null && !title.isBlank()) return title.trim();
        } catch (Exception e) {
            log.debug("Extract namespace failed for {}: {}", itemUrl, e.getMessage());
        }
        return fallbackName;
    }

    /**
     * Find the class ID for a mod on MC百科
     */
    private String findModClassId(String modId) {
        try {
            String url = "https://search.mcmod.cn/s?key=" + URLEncoder.encode(modId, StandardCharsets.UTF_8);
            Document doc = Jsoup.connect(url).userAgent(UA).timeout(10000).get();
            Elements links = doc.select("a[href*=/class/]");
            for (Element a : links) {
                String href = a.attr("href");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("/class/(\\d+)").matcher(href);
                if (m.find()) return m.group(1);
            }
        } catch (Exception e) {
            log.debug("mcmod.cn search failed for {}: {}", modId, e.getMessage());
        }
        return null;
    }

    // ==================== Core fetch ====================

    private WebPage fetchPage(String url, String source) {
        try {
            Document doc = Jsoup.connect(url).userAgent(UA).timeout(12000).get();
            StringBuilder content = new StringBuilder();
            content.append("Source: ").append(doc.title()).append("\nURL: ").append(url).append("\n\n");

            Elements paras = doc.select("p, li, h2, h3, h4, td, .mw-parser-output > *");
            for (Element e : paras) {
                String text = e.text().trim();
                if (text.isEmpty()) continue;
                String tag = e.tagName();
                if (tag.startsWith("h")) content.append("\n## ").append(text).append("\n\n");
                else content.append(text).append("\n");
            }

            // 如果结构化提取完全没有内容，回退到 body 全文
            if (content.length() == ("Source: " + doc.title() + "\nURL: " + url + "\n\n").length()) {
                content.append(doc.body().text());
            }

            return new WebPage(doc.title(), url, content.toString(), source, new LinkedHashMap<>());
        } catch (Exception e) {
            log.debug("Fetch failed {}: {}", url, e.getMessage());
        }
        return null;
    }

    // ==================== Data record ====================

    /**
     * Web 页面数据。
     *
     * @param title      页面标题
     * @param url        页面 URL
     * @param content    提取的文本内容
     * @param source     来源标识 (mcwiki / mcmod)
     * @param subWebPage 子网页映射: 命名空间(或标题) → 子网页URL
     *                   例如: "iceandfire:ghost" → "https://www.mcmod.cn/item/790985.html"
     */
    public record WebPage(String title, String url, String content, String source,
                          Map<String, String> subWebPage) {
    }
}
