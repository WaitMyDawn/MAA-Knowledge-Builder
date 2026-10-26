package yagen.waitmydawn.kb.service;

import java.util.*;

/** 仅使用文档明确提供的级别建立标题路径，不从标题文字推测父子关系。 */
final class HeadingHierarchy {
    private record Heading(int level,String text,String key) {}
    private final List<Heading> headings=new ArrayList<>();
    private int sequence;
    void accept(int level,String text) {
        if(text.isBlank()) return;
        headings.removeIf(h -> h.level()>=level);
        headings.add(new Heading(level,text.strip(),"s"+(++sequence)));
        if(path().length()>2000) throw new IllegalArgumentException("文档标题路径超过 2000 字符");
    }
    String leaf() {return headings.isEmpty()?"正文":headings.getLast().text();}
    String path() {return headings.isEmpty()?"正文":String.join(" > ",headings.stream().map(Heading::text).toList());}
    String lineage() {return "/root/"+String.join("/",headings.stream().map(Heading::key).toList())+(headings.isEmpty()?"":"/");}
}
