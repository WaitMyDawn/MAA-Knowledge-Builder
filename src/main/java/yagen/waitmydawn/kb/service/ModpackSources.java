package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.AnswerArtifacts;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** 整合包的块级证据保留在快照中，网页入口按地址合并展示。 */
public final class ModpackSources {
    private ModpackSources() {}
    private static final Pattern REFERENCE=Pattern.compile("\\[\\[source:([a-zA-Z0-9]+)]]|(?<!!)\\[[^\\]\\r\\n]*]\\(kb-source:([a-zA-Z0-9]+)\\)");
    private static final Pattern LINK=Pattern.compile("(?<!!)\\[([^\\]\\r\\n]+)]\\((https?://[^\\s)]+)\\)");
    private static final Pattern IMAGE=Pattern.compile("!\\[[^\\]\\r\\n]*]\\(kb-image:([a-zA-Z0-9]+)\\)");

    public static boolean applies(AnswerArtifacts artifacts) {
        return artifacts!=null && artifacts.scopeId()!=null && artifacts.scopeId().startsWith("mcmod:modpack:");
    }

    /** 仅做地址身份归一化，不执行 DNS 或网络请求，也不删除有意义的查询参数。 */
    public static String canonicalUrl(String location) {
        if(!AnswerSourceLinks.safeUrl(location)) return null;
        try {
            URI uri=URI.create(location).normalize();
            String scheme=uri.getScheme().toLowerCase(Locale.ROOT),host=uri.getHost().toLowerCase(Locale.ROOT);
            int port=uri.getPort();
            if(port==80 && scheme.equals("http") || port==443 && scheme.equals("https")) port=-1;
            String authority=host.contains(":")?"["+host+"]":host;
            if(port>=0) authority+=":"+port;
            String path=uri.getRawPath();if(path==null || path.isEmpty()) path="/";
            return scheme+"://"+authority+path+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
        } catch(IllegalArgumentException e) {return null;}
    }

    public static Set<String> inlineSourceIds(String answer,AnswerArtifacts artifacts) {
        var urls=new HashSet<String>();var links=LINK.matcher(AnswerMedia.referenceText(answer));
        while(links.find()) urls.add(canonicalUrl(links.group(2)));
        var ids=new LinkedHashSet<String>();
        for(var source:artifacts.sources()) {
            String url=canonicalUrl(source.location());
            if(url!=null && urls.contains(url)) ids.add(source.id());
        }
        return ids;
    }

    /** 支持新回答和旧快照回放，重复执行不会增加来源入口。 */
    public static String format(String answer,AnswerArtifacts artifacts) {
        if(!applies(artifacts)) return answer;
        answer=Objects.toString(answer,"");
        var sources=new LinkedHashMap<String,AnswerArtifacts.Source>();artifacts.sources().forEach(s -> sources.put(s.id(),s));
        var cited=new LinkedHashSet<String>();
        var matcher=REFERENCE.matcher(AnswerMedia.referenceText(answer));var out=new StringBuilder();int end=0;
        while(matcher.find()) {
            out.append(answer,end,matcher.start());end=matcher.end();
            String id=matcher.group(1)==null?matcher.group(2):matcher.group(1);
            if(sources.containsKey(id)) cited.add(id);
        }
        out.append(answer.substring(end));String text=out.toString();
        var inlineUrls=new HashSet<String>();
        var links=LINK.matcher(AnswerMedia.referenceText(text));out=new StringBuilder();end=0;
        while(links.find()) {
            out.append(text,end,links.start());end=links.end();
            String url=canonicalUrl(links.group(2));
            var source=sources.values().stream().filter(s -> url!=null && url.equals(canonicalUrl(s.location()))).findFirst().orElse(null);
            if(source==null) {out.append(text,links.start(),links.end());continue;}
            cited.add(source.id());
            String label=links.group(1).replace("**","").strip();
            if(!label.equals(source.title()) && !label.equals(artifacts.scopeLabel())) {
                if(inlineUrls.add(url)) out.append(text,links.start(),links.end());
                else out.append(links.group(1));
            }
        }
        out.append(text.substring(end));text=out.toString();
        // 旧版的兜底来源行在移除引用后会留下空标签。
        var emptyLines=Pattern.compile("(?m)^[ \\t]*(?:[-*]|资料来源[：:][ \\t·]*)[ \\t]*$")
                .matcher(AnswerMedia.referenceText(text));
        out=new StringBuilder();end=0;
        while(emptyLines.find()) {out.append(text,end,emptyLines.start());end=emptyLines.end();}
        out.append(text.substring(end));text=out.toString().strip();
        var images=IMAGE.matcher(AnswerMedia.referenceText(text));var usedPictures=new HashSet<String>();
        while(images.find()) usedPictures.add(images.group(1));
        for(var picture:artifacts.pictures()) if(usedPictures.contains(picture.id()) && sources.containsKey(picture.sourceId())) cited.add(picture.sourceId());
        if(cited.isEmpty()) cited.addAll(sources.keySet());
        var pages=new LinkedHashMap<String,AnswerArtifacts.Source>();
        for(String id:cited) {
            var source=sources.get(id);String url=canonicalUrl(source.location());
            if(url!=null && !inlineUrls.contains(url)) pages.putIfAbsent(url,source);
        }
        if(!pages.isEmpty()) {
            var footer=new StringBuilder("\n\n资料来源：\n");
            pages.values().forEach(s -> footer.append("- [").append(s.title().replaceAll("[\\[\\]\\r\\n]"," "))
                    .append("](kb-source:").append(s.id()).append(")\n"));
            text+=footer.toString().stripTrailing();
        }
        return text;
    }
}
