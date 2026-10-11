package yagen.waitmydawn.kb.service;

import org.jsoup.nodes.Element;
import java.util.ArrayList;
import java.util.List;

/** 表格行携带章节上下文，图标型单元格保留名称，避免掉落表和配方表被静默丢弃。 */
public final class WikiBodyExtractor {
    private WikiBodyExtractor() {}

    public static String extract(Element main) {
        Element clone=main.clone();
        clone.select("style, script, .mw-editsection, .toc, .msgbox, .hatnote, .navbox, .printfooter, .catlinks, .comment, .comments").remove();
        StringBuilder content=new StringBuilder();
        String section="正文";
        for(Element element:clone.select("p, h2, h3, h4, .common-text-title, li:not(:has(p)), table")) {
            if(element.parents().stream().anyMatch(p -> p.tagName().equals("table"))) continue;
            if(element.tagName().startsWith("h") || element.hasClass("common-text-title")) {
                section=element.text().trim();content.append("\n## ").append(section).append('\n');
            } else if(element.tagName().equals("table")) {
                List<String> headers=new ArrayList<>();
                for(Element row:element.select("tr")) {
                    if(row.closest("table")!=element) continue;
                    List<String> cells=new ArrayList<>();
                    boolean hasData=false;
                    for(Element cell:row.children()) {
                        if(!List.of("td","th").contains(cell.tagName())) continue;
                        hasData|=cell.tagName().equals("td");
                        String text=cell.text().trim();
                        if(text.isBlank()) {
                            var names=new java.util.LinkedHashSet<String>();
                            for(Element label:cell.select("a[title], img[alt]")) {
                                String name=label.hasAttr("title")?label.attr("title"):label.attr("alt");
                                if(!name.isBlank()) names.add(name);
                            }
                            text=String.join(" / ",names);
                        }
                        cells.add(text);
                    }
                    String text=String.join(" | ",cells);
                    if(text.replace("|","").isBlank()) continue;
                    boolean header=!hasData;
                    // 行标题 th 与数据 td 同行时不是列标题；累计所有行标题会造成二次方文本膨胀。
                    if(header) { headers.clear(); headers.add(text); }
                    content.append('[').append(section).append(" 表格");
                    if(!header && !headers.isEmpty()) content.append("；表头：").append(String.join(" / ",headers));
                    content.append("] ").append(text).append('\n');
                }
            } else if(!element.text().isBlank()) content.append(element.text().trim()).append('\n');
        }
        return content.toString();
    }
}
