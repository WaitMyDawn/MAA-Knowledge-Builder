package yagen.waitmydawn.kb.service;

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
        Element main = !mcmod?doc.selectFirst(".mw-parser-output"):null;
        if(main==null)main = doc.selectFirst(mcmod
                ? ".post-content .text, .post-content.common-text, .item-content.common-text, .class-text .common-text, .class-text.common-text"
                : "article, main, [role=main], #mw-content-text, .mw-parser-output");
        if (main == null && !mcmod) main = doc.body();
        if (main == null) return new Parsed(name, "", "", List.of());
        main = main.clone();
        main.select("script,style,nav,header,footer,aside,form,.comment,.comments,.common-comment-block,.post-cc,.toc,.navbox,.mw-editsection,.advertisement,.ads,[hidden]").remove();
        var parser = new WebDocumentParser();
        parser.walk(main, url);
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
