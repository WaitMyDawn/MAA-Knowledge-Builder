package yagen.waitmydawn.kb.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.net.URI;
import java.util.*;

/** 在正文 DOM 中保留段落、章节和图片位置；不使用整页文本回退处理百科。 */
final class WebDocumentParser {
    record ImageRef(String url, String caption,String stepText) {
        ImageRef(String url,String caption){this(url,caption,"");}
    }
    record Block(String text, String heading, List<ImageRef> images,String headingPath,String lineage,String kind,String tableId) {
        Block(String text,String heading,List<ImageRef> images) {this(text,heading,images,heading,"/root/legacy-"+yagen.waitmydawn.kb.model.BuildRequest.fingerprint(heading).substring(0,12)+"/","PARAGRAPH",null);}
    }
    record Parsed(String title, String author, String publishedAt, List<Block> blocks) {}
    private final List<Block> blocks = new ArrayList<>();
    private final HeadingHierarchy headings=new HeadingHierarchy();
    private int tableSequence;
    private long characters;

    static Parsed parse(Document doc, String url) {
        boolean mcmod = isMcmod(url);
        Element title = doc.selectFirst(mcmod ? ".class-title h3, .postname h1, .postname, .itemname .name h5" : "article h1, main h1, h1");
        String name = title == null ? doc.title() : title.text().strip();
        Element main = body(doc,url);
        if (main == null) return new Parsed(name, "", "", List.of());
        main = main.clone();
        WebBodySanitizer.clean(main);
        main.select("script,style,nav,header,footer,aside,form,.comment,.comments,.common-comment-block,.post-cc,.toc,.navbox,.mw-editsection,.advertisement,.ads,[hidden],.uknowtoomuch,.uknow-too-much").remove();
        var parser = new WebDocumentParser();
        parser.walk(main, url);
        if (mcmod)
            for (Element table : doc.select(".item-table-frame table"))
                if (table.closest(".item-content") == null) parser.table(table, url);
        Element author = doc.selectFirst(mcmod ? ".post-user-name" : "meta[name=author], [rel=author]");
        Element date = doc.selectFirst("meta[property=article:published_time], time[datetime]");
        if (date == null && mcmod) date = doc.select("[data-original-title]").stream()
                .filter(e -> e.attr("data-original-title").startsWith("发布时间:") || e.attr("data-original-title").startsWith("发布时间："))
                .findFirst().orElse(null);
        return new Parsed(name.isBlank() ? url : name,
                author == null ? "" : (author.hasAttr("content") ? author.attr("content") : author.text()),
                date == null ? "" : date.hasAttr("content") ? date.attr("content") : date.hasAttr("datetime") ? date.attr("datetime") : date.attr("data-original-title"),
                List.copyOf(parser.blocks));
    }

    static Element body(Document doc,String url){
        boolean mcmod=isMcmod(url);
        Element main = !mcmod?doc.selectFirst(".mw-parser-output"):null;
        if(main==null)main = doc.selectFirst(mcmod
                ? ".post-content .text, .post-content.common-text, .item-content.common-text, .class-text .common-text, .class-text.common-text"
                : "article, main, [role=main], #mw-content-text, .mw-parser-output");
        if (main == null && !mcmod) main = doc.body();
        return main;
    }

    private void walk(Element element, String url) {
        if (element.tagName().matches("h[1-6]") || element.hasClass("common-text-title")) {
            int level=element.tagName().matches("h[1-6]")?Integer.parseInt(element.tagName().substring(1)):2;
            for(String name:element.classNames()) if(name.matches("common-text-title-[1-6]")) level=Integer.parseInt(name.substring(name.length()-1));
            headings.accept(level,element.text());return;
        }
        boolean paragraph = element.tagName().equals("p") || element.tagName().equals("table")
                || (element.tagName().equals("li") && element.select("p,table,ul,ol").isEmpty());
        if (paragraph) {
            Element section = element.selectFirst(".common-text-title");
            if(section!=null) walk(section,url);
            if(element.tagName().equals("table")) {table(element,url);return;}
            var copy=element.clone();copy.select(".common-text-title").remove();
            String text=copy.text();
            add(text,images(copy,url),element.tagName().equals("li")?"LIST_ITEM":"PARAGRAPH",null);return;
        }
        if (element.tagName().equals("img")) { add("", images(element, url),"IMAGE",null); return; }
        var pending=new StringBuilder();
        for(var node:element.childNodes()) {
            if(node instanceof org.jsoup.nodes.TextNode text) pending.append(text.getWholeText());
            else if(node instanceof Element child) {
                if(!pending.toString().isBlank()) add(pending.toString(),List.of(),"PARAGRAPH",null);
                pending.setLength(0);walk(child,url);
            }
        }
        if(!pending.toString().isBlank()) add(pending.toString(),List.of(),"PARAGRAPH",null);
    }

    private void add(String text,List<ImageRef> images,String kind,String tableId) {
        characters+=text.length()+headings.path().length();
        if(characters>8L*1024*1024 || blocks.size()>=20000) throw new IllegalArgumentException("网页正文超过结构化解析预算");
        if(!text.isBlank() || !images.isEmpty()) blocks.add(new Block(text.strip(),headings.leaf(),images,headings.path(),headings.lineage(),kind,tableId));
    }

    private void table(Element table,String url) {
        String id="table"+(++tableSequence);var headers=new ArrayList<String>();String firstRow="";
        record Carry(String value,int until) {}
        var carry=new HashMap<Integer,Carry>();int rowIndex=0;
        for(var row:table.select("tr")) {
            if(row.closest("table")!=table) continue;
            if(++rowIndex>10000) throw new IllegalArgumentException("表格行数超过 10000");
            var values=new TreeMap<Integer,String>();
            for(var entry:carry.entrySet()) if(entry.getValue().until()>=rowIndex) values.put(entry.getKey(),entry.getValue().value());
            boolean header=row.closest("thead")!=null;int column=0;
            for(var cell:row.children()) {
                if(!Set.of("td","th").contains(cell.tagName())) continue;
                while(values.containsKey(column)) column++;
                int colspan=span(cell.attr("colspan"),64),rowspan=span(cell.attr("rowspan"),10000);
                if(column+colspan>64) throw new IllegalArgumentException("表格列数超过 64");
                for(int j=0;j<colspan;j++) {
                    values.put(column+j,cell.text());
                    if(rowspan>1) carry.put(column+j,new Carry(cell.text(),rowIndex+rowspan-1));
                }
                column+=colspan;
            }
            if(values.isEmpty()) continue;
            header|=row.children().stream().filter(e -> Set.of("td","th").contains(e.tagName())).allMatch(e -> e.tagName().equals("th") && !e.attr("scope").equalsIgnoreCase("row"));
            var cells=new ArrayList<String>();for(int j=0;j<=values.lastKey();j++) cells.add(values.getOrDefault(j,""));
            if(header) {
                for(int j=0;j<cells.size();j++) {
                    while(headers.size()<=j) headers.add("");
                    String previous=headers.get(j),value=cells.get(j);
                    if(!value.isBlank() && !previous.equals(value)) headers.set(j,previous.isBlank()?value:previous+" / "+value);
                }
                if(headers.stream().mapToInt(String::length).sum()>16000) throw new IllegalArgumentException("表头超过 16000 字符");
                continue;
            }
            String text=(!headers.isEmpty()?"表头："+String.join(" | ",headers)+"\n":firstRow.isEmpty()?"":"首行（原文）："+firstRow+"\n")+"表格行："+String.join(" | ",cells);
            if(firstRow.isEmpty()) firstRow=String.join(" | ",cells);
            add(text,images(row,url),"TABLE_ROW",id);
        }
        for (Element block : table.select(".TableBlock")) grid(block, url, id);
    }

    /** 保留合成网格的 item-id、行列和空位，而不是只拼出左侧材料文本。 */
    private void grid(Element block, String url, String tableId) {
        record Cell(String name, String itemId, int left, int top, int order) {}
        var cells = new ArrayList<Cell>();
        int order = 0;
        for (Element item : block.select(".item-table-hover[item-id]")) {
            String itemId = item.attr("item-id").strip();
            if (itemId.isBlank()) continue;
            String name = "";
            String title = item.attr("data-original-title");
            if (!title.isBlank()) {
                var parsed = Jsoup.parseBodyFragment(title);
                var label = parsed.selectFirst(".item-table-hover-t1");
                name = label == null ? parsed.text() : label.text();
            }
            if (name.isBlank()) name = item.selectFirst("img") == null ? "" : item.selectFirst("img").attr("alt");
            if (name.isBlank()) name = itemId;
            int left = 0, top = 0;
            String style = item.attr("style");
            int colon = style.indexOf(':');
            if (colon >= 0) {
                String[] parts = style.substring(colon + 1).strip().split("\\s+");
                if (parts.length >= 4) {
                    top = pixel(parts[0]);
                    left = pixel(parts[3]);
                }
            }
            cells.add(new Cell(name.strip(), itemId, left, top, order++));
        }
        if (cells.isEmpty()) return;
        Cell output = cells.getFirst();
        int maxLeft = cells.stream().mapToInt(Cell::left).max().orElse(0);
        for (var cell : cells) if (cell.left() == maxLeft) output = cell;
        var inputs = new ArrayList<>(cells);
        inputs.remove(output);
        int originLeft = inputs.stream().mapToInt(Cell::left).min().orElse(0);
        int originTop = inputs.stream().mapToInt(Cell::top).min().orElse(0);
        int cellSize = 36;
        var distances = new ArrayList<Integer>();
        for (int i = 0; i < inputs.size(); i++)
            for (int j = i + 1; j < inputs.size(); j++) {
                int dx = Math.abs(inputs.get(i).left() - inputs.get(j).left());
                int dy = Math.abs(inputs.get(i).top() - inputs.get(j).top());
                if (dx > 0) distances.add(dx);
                if (dy > 0) distances.add(dy);
            }
        if (!distances.isEmpty()) cellSize = Math.max(1, Collections.min(distances));
        int cols = 1, rows = 1;
        var placed = new HashMap<String, Cell>();
        for (var cell : inputs) {
            int col = Math.max(0, Math.round((cell.left() - originLeft) / (float) cellSize));
            int row = Math.max(0, Math.round((cell.top() - originTop) / (float) cellSize));
            cols = Math.max(cols, col + 1);
            rows = Math.max(rows, row + 1);
            placed.put(row + "/" + col, cell);
        }
        boolean shapeless = block.selectFirst(".orderly") != null;
        var text = new StringBuilder("配方网格：" + rows + " 行 × " + cols + " 列");
        if (shapeless) text.append("（无序合成）");
        text.append("\n");
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                var cell = placed.get(row + "/" + col);
                text.append("[").append(row + 1).append(",").append(col + 1).append("] ");
                text.append(cell == null ? "—" : cell.name() + " (mcmod-item:" + cell.itemId() + ")");
                text.append("\n");
            }
        }
        text.append("输出：").append(output.name()).append(" (mcmod-item:").append(output.itemId()).append(")");
        add(text.toString(), images(block, url), "RECIPE_GRID", tableId);
    }

    private static int pixel(String value) {
        try { return Integer.parseInt(value.replaceAll("[^0-9-]", "")); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static int span(String value,int max) {
        try {return Math.max(1,Math.min(max,Integer.parseInt(value)));}catch(NumberFormatException e) {return 1;}
    }

    private static List<ImageRef> images(Element block, String base) {
        var images = new LinkedHashMap<String, ImageRef>();
        var elements = block.tagName().equals("img") ? List.of(block) : block.select("img");
        for (Element img : elements) {
            String raw = "";
            for (String attr : List.of("data-src", "data-original", "data-lazy-src", "src"))
                if (!img.attr(attr).isBlank()) { raw = img.attr(attr).strip(); break; }
            try {
                String url = WebUrlPolicy.normalize(URI.create(base).resolve(raw).toString());
                String path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
                if (raw.isBlank() || path.contains("loading") || path.contains("loadfail") || path.endsWith("/none.jpg")
                        || path.contains("/avatar/") || path.contains("/logo")) continue;
                if (small(img.attr("width")) && small(img.attr("height"))) continue;
                String caption = img.attr("alt").strip();
                if (caption.isBlank()) caption = img.attr("title").strip();
                Element figure = img.closest("figure");
                if (figure != null && figure.selectFirst("figcaption") != null) caption = figure.selectFirst("figcaption").text();
                images.putIfAbsent(url, new ImageRef(url, caption,inlineContext(block,img)));
            } catch (IllegalArgumentException ignored) { }
        }
        return List.copyOf(images.values());
    }
    private static String inlineContext(Element block,Element image) {
        var nodes=new ArrayList<org.jsoup.nodes.Node>();
        org.jsoup.select.NodeTraversor.traverse(new org.jsoup.select.NodeVisitor(){
            public void head(org.jsoup.nodes.Node node,int depth){nodes.add(node);}
            public void tail(org.jsoup.nodes.Node node,int depth){}
        },block);
        var segment=new StringBuilder();String before="";boolean found=false;
        for(var node:nodes) {
            if(node instanceof Element element&&element.tagName().equals("img")) {
                if(element==image){before=segment.toString().strip();segment.setLength(0);found=true;}
                else if(found)break;else segment.setLength(0);
            }else if(node instanceof org.jsoup.nodes.TextNode text)segment.append(text.getWholeText()).append(' ');
            else if(node instanceof Element element&&element.tagName().equals("br"))segment.append('\n');
        }
        if(before.length()>=6)return before.substring(Math.max(0,before.length()-900));
        String after=segment.toString().strip();return after.substring(0,Math.min(after.length(),900));
    }

    private static boolean small(String value) {
        try { int size = Integer.parseInt(value); return size > 0 && size <= 2; }
        catch (NumberFormatException e) { return false; }
    }

    static boolean isMcmod(String url) {
        String host = URI.create(url).getHost();
        return "www.mcmod.cn".equalsIgnoreCase(host) || "mcmod.cn".equalsIgnoreCase(host);
    }
}
