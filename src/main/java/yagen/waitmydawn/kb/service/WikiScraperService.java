package yagen.waitmydawn.kb.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外部知识爬取: MC Wiki (中文镜像, BWIKI) + MC百科。
 *
 * <p>安全机制（防限流 / 防 ban IP）：
 * <ul>
 *   <li>每域名最小请求间隔 + 随机抖动（politeWait），所有实例共享</li>
 *   <li>403/429/5xx 指数退避 + 抖动，并尊重 Retry-After 响应头</li>
 *   <li>每域名单次运行请求预算，超限主动放弃，避免触发封禁</li>
 *   <li>响应缓存（TTL + 容量上限），同一 URL 不重复抓取</li>
 *   <li>反爬页面识别（短响应 / Cloudflare 质询 / 验证码特征），命中即丢弃</li>
 *   <li>浏览器头 / Cookie 会话复用 / Referer 伪装</li>
 *   <li>可选 HTTP 代理：-Dmaa.scraper.proxy=host:port（IP 被封时的逃生通道）</li>
 * </ul>
 */
public class WikiScraperService {

    private static final Logger log = LoggerFactory.getLogger(WikiScraperService.class);

    // ==================== 站点基地址 ====================

    /** 中文 Minecraft Wiki 镜像 (BWIKI) — 官方站 (zh.minecraft.wiki) 被 Cloudflare 拦截，全面改用镜像。 */
    private static final String MC_WIKI_BASE = "https://wiki.biligame.com/mc";
    private static final String MC_WIKI_SEARCH_API =
            MC_WIKI_BASE + "/api.php?action=opensearch&format=json&limit=5&search=";
    private static final String MCMOD_BASE = "https://www.mcmod.cn";
    private static final String MCMOD_SEARCH_BASE = "https://search.mcmod.cn/s?key=";

    // ==================== 浏览器头伪装 ====================

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final String ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8";
    private static final String ACCEPT_LANG = "zh-CN,zh;q=0.9,en;q=0.8";
    // 只声明 gzip/deflate：Jsoup 能自动解压这两种，br 无法解压会得到乱码正文
    private static final String ACCEPT_ENC = "gzip, deflate";

    // ==================== 限流与防封配置 ====================

    private static final int CATEGORY_DELAY_MS = 1000;              // 分类页额外礼让间隔
    private static final int RETRY_BASE_DELAY_MS = 1000;             // 退避基数
    private static final int MAX_RETRIES_DEFAULT = 3;                // 默认最大重试次数
    private static final int MIN_VALID_BODY_LEN = 200;               // 低于此长度视为异常/反爬
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;        // 响应缓存 TTL
    private static final int CACHE_MAX_ENTRIES = 256;                // 响应缓存容量

    /** 每域名最小请求间隔（可 -Dmaa.scraper.minIntervalMs=... 覆盖） */
    private static final long MIN_REQUEST_INTERVAL_MS;
    /** 单次运行每域名请求预算（可 -Dmaa.scraper.maxRequestsPerHost=... 覆盖） */
    private static final int MAX_REQUESTS_PER_HOST;
    /** 随机抖动上限 */
    private static final long JITTER_MAX_MS = 400;

    /** 反爬/质询页特征标记（命中即丢弃，不入库） */
    private static final String[] BLOCK_MARKERS = {
            "browser check", "cf-chl", "cf_chl", "__cf_chl",
            "人机验证", "验证码", "captcha", "please wait"
    };

    // ==================== 可选代理 ====================

    private static final String PROXY_HOST;
    private static final int PROXY_PORT;

    static {
        long minInterval = 800;
        int budget = 2000;
        try {
            minInterval = Long.parseLong(System.getProperty("maa.scraper.minIntervalMs", "800"));
            budget = Integer.parseInt(System.getProperty("maa.scraper.maxRequestsPerHost", "2000"));
        } catch (NumberFormatException ignored) {
        }
        MIN_REQUEST_INTERVAL_MS = minInterval;
        MAX_REQUESTS_PER_HOST = budget;

        String proxy = System.getProperty("maa.scraper.proxy");
        if (proxy == null || proxy.isBlank()) proxy = System.getenv("MAA_SCRAPER_PROXY");
        String host = null;
        int port = 0;
        if (proxy != null && !proxy.isBlank()) {
            String[] parts = proxy.split(":");
            if (parts.length == 2) {
                try {
                    host = parts[0].trim();
                    port = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        PROXY_HOST = host;
        PROXY_PORT = port;
    }

    // ==================== 全实例共享的限流/缓存状态 ====================

    private static final Object RATE_LOCK = new Object();
    private static final Map<String, Long> HOST_NEXT_ALLOWED = new ConcurrentHashMap<>();
    private static final Map<String, Integer> HOST_REQUEST_COUNT = new ConcurrentHashMap<>();
    private static final Map<String, CacheEntry> FETCH_CACHE = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record CacheEntry(FetchedPage page, long expireAt) {}

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
     * Fetch a single MC Wiki page (中文镜像 BWIKI)
     */
    public WebPage fetchMcWiki(String pageName) {
        String url = MC_WIKI_BASE + "/" + pageName.trim().replace(' ', '_');
        return fetchPage(url, "mcwiki");
    }

    /**
     * 优先直接访问 {MC_WIKI_BASE}/{topic}，失败时再走镜像站内搜索。
     * MediaWiki 自带重定向能力，例如 /mc/龙 会被重定向到 /mc/末影龙。
     */
    public FetchedPage fetchMcWikiWithFallback(String topic) {
        String directUrl = MC_WIKI_BASE + "/" + topic.trim().replace(' ', '_');
        log.info("MC Wiki(镜像): trying direct URL {}", directUrl);
        FetchedPage direct = fetchPageWithRegistries(directUrl, "mcwiki");
        if (isUsable(direct)) {
            log.info("MC Wiki(镜像): direct fetch OK for {}", directUrl);
            return direct;
        }
        log.warn("MC Wiki(镜像): direct fetch failed or empty for {}", directUrl);

        String searchedUrl = searchMcWikiUrl(topic);
        if (searchedUrl != null) {
            log.info("MC Wiki(镜像): trying search result URL {}", searchedUrl);
            FetchedPage searched = fetchPageWithRegistries(searchedUrl, "mcwiki");
            if (isUsable(searched)) {
                log.info("MC Wiki(镜像): search result fetch OK for {}", searchedUrl);
                return searched;
            }
            log.warn("MC Wiki(镜像): search result fetch failed or empty for {}", searchedUrl);
        }
        return null;
    }

    private static boolean isUsable(FetchedPage p) {
        return p != null && p.page() != null && p.page().content() != null
                && p.page().content().length() > 80;
    }

    /** 通过 BWIKI 镜像的 MediaWiki opensearch API 定位最可能的页面 URL。 */
    public String searchMcWikiUrl(String topic) {
        try {
            String searchUrl = MC_WIKI_SEARCH_API
                    + URLEncoder.encode(topic, StandardCharsets.UTF_8);
            log.info("MC Wiki(镜像): searching API {}", searchUrl);
            FetchOutcome out = executeRaw(searchUrl, null, MC_WIKI_BASE + "/首页", true, 2);
            if (out.status() != 200 || out.body() == null || out.body().isBlank()) {
                log.warn("MC Wiki(镜像): search API failed for {} (HTTP {})", topic, out.status());
                return null;
            }
            // opensearch 返回 [query, titles, descriptions, urls]
            JsonNode root = MAPPER.readTree(out.body());
            JsonNode urls = root.path(3);
            for (JsonNode node : urls) {
                String resultUrl = node.asText();
                if (resultUrl.startsWith(MC_WIKI_BASE)) {
                    log.info("MC Wiki(镜像): first search hit {}", resultUrl);
                    return resultUrl;
                }
            }
            log.warn("MC Wiki(镜像): no search result found for {}", topic);
        } catch (Exception e) {
            log.warn("MC Wiki(镜像) search failed for {}: {}", topic, e.getMessage());
        }
        return null;
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
     * 抓取网页原始 HTML，在提取正文的同时解析注册名。
     * MC百科从 data-command="/summon xxx:xxx ..." 中提取，
     * MC Wiki 从 <code>...minecraft.xxx...</code> 中提取。
     */
    public FetchedPage fetchPageWithRegistries(String url, String source) {
        return fetchCached(url, source, true, null, MAX_RETRIES_DEFAULT);
    }

    /** 带缓存与限流的统一抓取入口。 */
    private FetchedPage fetchCached(String url, String source, boolean withRegistries,
                                    Map<String, String> cookies, int maxRetries) {
        CacheEntry cached = FETCH_CACHE.get(url);
        if (cached != null) {
            if (cached.expireAt() > System.currentTimeMillis()) {
                log.debug("Fetch cache hit: {}", url);
                return cached.page();
            }
            FETCH_CACHE.remove(url);
        }

        FetchOutcome out = executeRaw(url, cookies, refererFor(source), true, maxRetries);
        if (out.status() != 200 || out.body() == null || out.body().isBlank()) {
            log.warn("Fetch failed {}: HTTP {}", url, out.status());
            return null;
        }
        if (looksBlocked(out.body())) {
            log.warn("Possible anti-bot page for {} ({} chars), ignored", url, out.body().length());
            return null;
        }

        Document doc = Jsoup.parse(out.body());
        List<String> registries = withRegistries
                ? ("mcmod".equals(source) ? extractMcmodRegistries(out.body())
                                          : extractMcWikiRegistries(out.body()))
                : List.of();
        FetchedPage fetched = new FetchedPage(toWebPage(doc, url, source), registries);
        cachePut(url, fetched);
        return fetched;
    }

    private static String refererFor(String source) {
        return "mcwiki".equals(source) ? MC_WIKI_BASE + "/首页" : MCMOD_BASE + "/";
    }

    private void cachePut(String url, FetchedPage page) {
        if (FETCH_CACHE.size() >= CACHE_MAX_ENTRIES) {
            String oldestKey = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, CacheEntry> e : FETCH_CACHE.entrySet()) {
                if (e.getValue().expireAt() < oldest) {
                    oldest = e.getValue().expireAt();
                    oldestKey = e.getKey();
                }
            }
            if (oldestKey != null) FETCH_CACHE.remove(oldestKey);
        }
        FETCH_CACHE.put(url, new CacheEntry(page, System.currentTimeMillis() + CACHE_TTL_MS));
    }

    /** 反爬页识别：过短响应体或出现质询/验证码特征即视为被拦截。 */
    private boolean looksBlocked(String body) {
        if (body == null || body.trim().length() < MIN_VALID_BODY_LEN) return true;
        String lower = body.toLowerCase(Locale.ROOT);
        for (String marker : BLOCK_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    // ==================== 统一请求执行器（限流 + 退避 + 预算） ====================

    private record FetchOutcome(int status, String body, int attempts) {}

    /**
     * 统一请求执行器：每域名限速 → 请求预算 → 浏览器头 → Cookie 会话 → 指数退避+抖动。
     * 403/429 按 Retry-After（若提供）或 1s*2^n 退避；5xx 短退避；404 不重试。
     */
    private FetchOutcome executeRaw(String url, Map<String, String> cookies, String referer,
                                    boolean captureCookies, int maxRetries) {
        int lastStatus = 0;
        int lastBodyLen = -1;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            politeWait(url);
            if (overBudget(url)) {
                log.warn("Request budget exceeded for {} — skip further requests", hostOf(url));
                return new FetchOutcome(lastStatus, null, attempt + 1);
            }
            try {
                var conn = Jsoup.connect(url)
                        .userAgent(UA)
                        .header("Accept", ACCEPT)
                        .header("Accept-Language", ACCEPT_LANG)
                        .header("Accept-Encoding", ACCEPT_ENC)
                        .header("Connection", "keep-alive")
                        .timeout(15000)
                        .followRedirects(true)
                        .ignoreHttpErrors(true)
                        .ignoreContentType(true);
                if (referer != null) conn.header("Referer", referer);
                if (PROXY_HOST != null) conn.proxy(PROXY_HOST, PROXY_PORT);
                if (cookies != null && !cookies.isEmpty()) conn.cookies(cookies);

                var resp = conn.execute();
                int status = resp.statusCode();
                String body = resp.body();

                if (captureCookies && cookies != null) {
                    Map<String, String> newCookies = resp.cookies();
                    if (!newCookies.isEmpty()) cookies.putAll(newCookies);
                }
                lastStatus = status;
                lastBodyLen = body != null ? body.length() : 0;

                if (status == 200 && body != null && body.length() > MIN_VALID_BODY_LEN) {
                    return new FetchOutcome(200, body, attempt + 1);
                }
                if (status == 404) {
                    return new FetchOutcome(404, body, attempt + 1); // 永久性错误，不重试
                }

                long delay;
                if (status == 403 || status == 429) {
                    long retryAfter = parseRetryAfter(resp.header("Retry-After"));
                    delay = (retryAfter > 0 ? retryAfter * 1000L
                            : (long) RETRY_BASE_DELAY_MS * (1L << attempt)) + jitter();
                    log.warn("HTTP {} for {} (attempt {}/{}) — backing off {}ms",
                            status, url, attempt + 1, maxRetries, delay);
                } else if (status >= 500) {
                    delay = (long) RETRY_BASE_DELAY_MS * (attempt + 1) + jitter();
                    log.warn("HTTP {} for {} (attempt {}/{}) — backing off {}ms",
                            status, url, attempt + 1, maxRetries, delay);
                } else {
                    delay = RETRY_BASE_DELAY_MS + jitter();
                }
                sleepQuietly(delay);
            } catch (java.net.SocketTimeoutException e) {
                lastStatus = 0;
                log.warn("Socket timeout for {} (attempt {}/{})", url, attempt + 1, maxRetries);
                sleepQuietly((long) RETRY_BASE_DELAY_MS * (attempt + 1) + jitter());
            } catch (Exception e) {
                lastStatus = 0;
                log.debug("Fetch attempt failed for {}: {}", url, e.getMessage());
                sleepQuietly(RETRY_BASE_DELAY_MS + jitter());
            }
        }
        log.warn("Fetch gave up after {} attempts for {} (last HTTP {}, body {} chars)",
                maxRetries, url, lastStatus, lastBodyLen);
        return new FetchOutcome(lastStatus, null, maxRetries);
    }

    private static long parseRetryAfter(String header) {
        if (header == null || header.isBlank()) return -1;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static long jitter() {
        return ThreadLocalRandom.current().nextLong(0, JITTER_MAX_MS + 1);
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 每域名最小请求间隔（含抖动），所有实例共享，避免突发请求触发限流。 */
    private void politeWait(String url) {
        String host = hostOf(url);
        synchronized (RATE_LOCK) {
            long now = System.currentTimeMillis();
            long wait = HOST_NEXT_ALLOWED.getOrDefault(host, 0L) - now;
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            HOST_NEXT_ALLOWED.put(host, System.currentTimeMillis() + MIN_REQUEST_INTERVAL_MS + jitter());
        }
    }

    /** 每域名请求预算（单次运行），超限返回 true 并停止该域后续请求。 */
    private boolean overBudget(String url) {
        String host = hostOf(url);
        int count = HOST_REQUEST_COUNT.merge(host, 1, Integer::sum);
        return count > MAX_REQUESTS_PER_HOST;
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 从 MC百科网页提取 /summon 等 data-command 中的注册名。 */
    public List<String> extractMcmodRegistries(String html) {
        Set<String> result = new LinkedHashSet<>();
        if (html == null) return List.of();
        Matcher commandMatcher = Pattern.compile("data-command=\"([^\"]+)\"").matcher(html);
        Pattern registryPattern = Pattern.compile("([a-z_][a-z0-9_]*:[a-z_][a-z0-9_]*)");
        while (commandMatcher.find()) {
            String command = commandMatcher.group(1);
            Matcher regMatcher = registryPattern.matcher(command);
            while (regMatcher.find()) result.add(regMatcher.group(1));
        }
        return new ArrayList<>(result);
    }

    /** 从 MC Wiki 网页提取 <code>block.minecraft.oak_fence_gate</code> 类注册名。 */
    public List<String> extractMcWikiRegistries(String html) {
        Set<String> result = new LinkedHashSet<>();
        if (html == null) return List.of();
        Matcher codeMatcher = Pattern.compile("<code[^>]*>(.*?)</code>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (codeMatcher.find()) {
            String text = codeMatcher.group(1).replaceAll("<[^>]+>", "").trim();
            int idx = text.toLowerCase().indexOf(".minecraft.");
            if (idx < 0) continue;
            String after = text.substring(idx + ".minecraft.".length());
            String name = after.split("\\.")[0].trim();
            if (name.matches("[a-z_][a-z0-9_]*")) {
                result.add("minecraft:" + name);
            }
        }
        return new ArrayList<>(result);
    }

    /** 把 Jsoup Document 转换为统一的 WebPage 文本结构，按站点清洗导航/页脚等噪音。 */
    private WebPage toWebPage(Document doc, String url, String source) {
        String title = doc.title();
        StringBuilder content = new StringBuilder();

        if ("mcmod".equals(source)) {
            Element nameEl = doc.selectFirst(".itemname .name h5, .itemname .name");
            if (nameEl != null) title = nameEl.text().trim();
            content.append("名称：").append(title).append("\n");

            Elements infoRows = doc.select(".item-info-table tr");
            for (Element tr : infoRows) {
                Elements tds = tr.select("td");
                if (tds.size() < 2) continue;
                String k = tds.get(0).text().trim();
                String v = tds.get(1).text().trim();
                if (!k.isEmpty() && !v.isEmpty()) content.append(k).append("：").append(v).append("\n");
            }

            Element main = doc.selectFirst(".item-content.common-text");
            if (main != null) {
                for (Element e : main.select("p, .common-text-title, h2, h3, h4")) {
                    String text = e.text().trim();
                    if (text.isEmpty()) continue;
                    if (e.hasClass("common-text-title")) content.append("\n## ").append(text).append("\n");
                    else content.append(text).append("\n");
                }
            }
        } else {
            content.append("标题：").append(title).append("\n");
            Element main = doc.selectFirst("#mw-content-text, .mw-parser-output");
            if (main != null) {
                Element clone = main.clone();
                clone.select("style, script, .mw-editsection, .toc, .infobox, "
                        + ".msgbox, .hatnote, .navbox, .printfooter, .catlinks").remove();
                for (Element e : clone.select("p, h2, h3, h4, li:not(:has(p))")) {
                    String text = e.text().trim();
                    if (text.isEmpty()) continue;
                    if (e.tagName().startsWith("h")) content.append("\n## ").append(text).append("\n");
                    else content.append(text).append("\n");
                }
            }
        }

        if (content.length() <= 20) {
            content.append(doc.body().text());
        }
        return new WebPage(title, url, cleanAndDedupe(content.toString()), source, new LinkedHashMap<>());
    }

    /** 去重、去空行和明显非内容行。 */
    private String cleanAndDedupe(String text) {
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\\n")) {
            String clean = line.trim();
            if (clean.isEmpty() || clean.length() < 2) continue;
            if (clean.matches("[\\p{Punct}\\s]+")) continue;
            if (seen.add(clean)) result.append(clean).append("\n");
        }
        return result.toString().trim();
    }

    /**
     * Fetch a category page with retry, browser headers, cookies, and exponential backoff.
     */
    private CategoryFetchResult fetchCategoryWithRetry(String url, int maxRetries,
                                                        Map<String, String> cookies, String referer) {
        FetchOutcome out = executeRaw(url, cookies,
                referer != null ? referer : MCMOD_BASE + "/", true, maxRetries);
        if (out.status() == 200 && out.body() != null && out.body().length() > 200) {
            return new CategoryFetchResult(Jsoup.parse(out.body()), 200, out.body().length(), out.attempts());
        }
        return new CategoryFetchResult(null, out.status(),
                out.body() != null ? out.body().length() : 0, out.attempts());
    }

    /** Fetch a page and capture cookies from the response */
    private WebPage fetchPageWithCookies(String url, String source, Map<String, String> cookies) {
        FetchOutcome out = executeRaw(url, cookies, MCMOD_BASE + "/", true, MAX_RETRIES_DEFAULT);
        if (out.status() != 200 || out.body() == null || out.body().isBlank()) {
            log.debug("Fetch failed {}: HTTP {}", url, out.status());
            return null;
        }
        if (looksBlocked(out.body())) {
            log.warn("Possible anti-bot page for {} ({} chars), ignored", url, out.body().length());
            return null;
        }
        return toWebPage(Jsoup.parse(out.body()), url, source);
    }

    /** Extract namespace from an item page, using session cookies */
    private String extractNamespaceWithCookies(String itemUrl, String fallbackName,
                                                Map<String, String> cookies) {
        try {
            FetchOutcome out = executeRaw(itemUrl, cookies, MCMOD_BASE + "/", true, MAX_RETRIES_DEFAULT);
            if (out.status() != 200 || out.body() == null) return fallbackName;
            Document doc = Jsoup.parse(out.body());

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
            String url = MCMOD_SEARCH_BASE + URLEncoder.encode(modId, StandardCharsets.UTF_8);
            FetchOutcome out = executeRaw(url, null, MCMOD_BASE + "/", true, 2);
            if (out.status() != 200 || out.body() == null) return null;
            Document doc = Jsoup.parse(out.body());
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
        FetchedPage fetched = fetchCached(url, source, false, null, 2);
        return fetched == null ? null : fetched.page();
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

    /** 网页正文 + 从原始 HTML 提取的注册名清单。 */
    public record FetchedPage(WebPage page, List<String> registries) {}
}
