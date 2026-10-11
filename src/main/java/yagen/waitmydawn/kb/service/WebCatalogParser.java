package yagen.waitmydawn.kb.service;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import yagen.waitmydawn.kb.model.WebCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 从嵌套分类表及跨行表头恢复分类路径，不从扁平文本推断成员关系。 */
public final class WebCatalogParser {
    private final Map<String, WebCatalog.Node> nodes = new LinkedHashMap<>();
    private final Map<String, WebCatalog.Member> members = new LinkedHashMap<>();
    private String modId, url;
    private int top;

    public WebCatalog parse(Document doc, String modId, String url, int top, String name) {
        nodes.clear(); members.clear(); this.modId = modId; this.url = url; this.top = top;
        List<String> root = List.of(name);
        node(root);
        for (Element table : doc.select("table")) {
            if (table.select("th.item-list-type-left").isEmpty()) continue;
            Element ancestor = table.parent().closest("table");
            if (ancestor == null || ancestor.select("th.item-list-type-left").isEmpty()) walk(table, root);
        }
        return new WebCatalog(List.copyOf(nodes.values()), List.copyOf(members.values()));
    }

    private record Span(String name, int remaining) {}
    private void walk(Element table, List<String> prefix) {
        Map<Integer, Span> spans = new TreeMap<>();
        for (Element row : table.select("tr")) {
            if (row.closest("table") != table || row.closest("thead") != null) continue;
            Map<Integer, Span> next = new TreeMap<>();
            for (var e : spans.entrySet()) if (e.getValue().remaining > 1)
                next.put(e.getKey(), new Span(e.getValue().name, e.getValue().remaining - 1));
            Map<Integer, String> headers = new TreeMap<>();
            spans.forEach((col, span) -> headers.put(col, span.name));
            int col = 0;
            for (Element cell : row.children()) {
                if (!Set.of("td", "th").contains(cell.tagName())) continue;
                while (spans.containsKey(col)) col++;
                int width = positive(cell.attr("colspan"));
                if (cell.hasClass("item-list-type-left")) {
                    String name = cell.ownText().trim();
                    if (name.isEmpty()) name = cell.text().trim();
                    if (!name.isBlank()) {
                        headers.put(col, name);
                        int height = positive(cell.attr("rowspan"));
                        if (height > 1) next.put(col, new Span(name, height - 1));
                    }
                } else if (cell.hasClass("item-list-type-right")) {
                    List<String> path = new ArrayList<>(prefix);
                    for (var h : headers.entrySet()) if (h.getKey() < col) path.add(h.getValue());
                    String id = node(path);
                    for (Element link : cell.select("a[href]")) {
                        if (link.closest("table") != table) continue;
                        String href = link.absUrl("href");
                        if (href.isBlank()) href = "https://www.mcmod.cn" + link.attr("href");
                        if (!href.matches("https://www\\.mcmod\\.cn/item/\\d+\\.html")) continue;
                        String cn = link.attr("data-cn"); if (cn.isBlank()) cn = link.text().trim();
                        if (cn.isBlank()) continue;
                        String en = link.attr("data-en");
                        String label = en.isBlank() ? cn : cn + "(" + en + ")";
                        String role = path.stream().anyMatch(p -> p.contains("技术性")) ? "technical"
                                : path.stream().anyMatch(p -> p.contains("未完成")) ? "unfinished" : "entry";
                        if (link.parents().stream().anyMatch(p -> p.hasClass("item-list-merge") || p.hasClass("skip"))) role = "merged-child";
                        members.putIfAbsent(id + "\n" + href, new WebCatalog.Member(id, href, label, cn, en, role));
                    }
                    for (Element child : cell.select("table"))
                        if (child.parent().closest("table") == table) walk(child, path);
                }
                col += width;
            }
            spans = next;
        }
    }

    private String node(List<String> path) {
        String id = hash(modId + "\n" + url + "\n" + String.join("\u001f", path));
        if (!nodes.containsKey(id)) {
            String parent = path.size() > 1 ? node(path.subList(0, path.size() - 1)) : null;
            nodes.put(id, new WebCatalog.Node(id, parent, modId, url, top, path.getLast(), List.copyOf(path)));
        }
        return id;
    }
    private static int positive(String value) { try { return Math.max(1, Integer.parseInt(value)); } catch (Exception e) { return 1; } }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
