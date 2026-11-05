package yagen.waitmydawn.kb.service;

import org.jsoup.nodes.Document;
import java.util.*;

/** 正文链接保留实际锚文本与段落条件；链接关系只提供后续阅读候选，不证明对象归属。 */
final class WebBodyLinks {
    record Link(String label,String url,String context) {}
    static List<Link> extract(Document document,String url){
        var root=WebDocumentParser.body(document,url);if(root==null)return List.of();
        root=root.clone();WebBodySanitizer.clean(root);
        root.select("nav,header,footer,aside,form,.comment,.comments,.toc,.navbox,.advertisement,.ads").remove();
        var links=new LinkedHashMap<String,Link>();
        for(var anchor:root.select("a[href]"))try{
            String target=WebUrlPolicy.normalize(anchor.absUrl("href"));
            String label=anchor.text().strip();if(label.isBlank())label=anchor.attr("title").strip();
            if(label.isBlank()||target.equals(WebUrlPolicy.normalize(url)))continue;
            String context=anchor.parent()==null?label:anchor.parent().text();
            links.putIfAbsent(target,new Link(label,target,context.substring(0,Math.min(400,context.length()))));
        }catch(IllegalArgumentException invalid){ }
        return List.copyOf(links.values());
    }
}
