package yagen.waitmydawn.kb.service;

import java.util.*;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.util.regex.*;

/**
 * 回答里的对象中文名按注册名对应的语言表校正。
 *
 * <p>只处理“名称（注册名/英文名）”这种显式配对，避免猜测改写：配对能解析到唯一对象时，
 * 用目录或语言表名称替换前面的写法；解析不到或存在歧义就保持原样。
 */
final class NameNormalizer {
    private static final Pattern PAIR=Pattern.compile("([\\u4e00-\\u9fa5]{2,12})(\\s*[（(]\\s*`?([a-z0-9_.-]+:[a-z0-9_./-]+)`?\\s*[）)])");
    /** 中文（English Name）配对：按英文名反查规范中文名。 */
    private static final Pattern ENGLISH_PAIR=Pattern.compile("([\\u4e00-\\u9fa5]{2,12})(\\s*[（(]\\s*([A-Z][A-Za-z0-9_'-]*(?: [A-Za-z0-9_'-]+){0,4})\\s*[）)])");

    private NameNormalizer(){}

    private static String resolveEnglish(RuntimeNameIndex names,WebCatalogStore catalog,String name,
                                         Collection<String> preferredMods,Collection<String> fallbackMods){
        var preferred=preferredMods==null?List.<String>of():List.copyOf(preferredMods);
        var fallback=fallbackMods==null?List.<String>of():List.copyOf(fallbackMods);
        String fromCatalog=uniqueChinese(catalog.findByName(name,preferred));
        if(fromCatalog==null&&!preferred.isEmpty()&&!fallback.isEmpty())
            fromCatalog=uniqueChinese(catalog.findByName(name,fallback));
        if(fromCatalog!=null)return fromCatalog;
        try{
            var resolution=names.resolve(name,null,null);
            if(resolution.status().equals("UNIQUE"))return names.nameOf(resolution.candidates().getFirst().registry());
        }catch(Exception ignored){}
        String slug=name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("^_+|_+$","");
        if(slug.isBlank())return null;
        var mods=new LinkedHashSet<String>();mods.addAll(preferred);mods.addAll(fallback);
        for(String mod:mods){
            try{
                String canonical=names.nameOf(mod+":"+slug);
                if(canonical!=null&&!canonical.isBlank())return canonical;
            }catch(Exception ignored){}
        }
        return null;
    }

    private static String uniqueChinese(List<WebCatalogStore.Entry> matches){
        var names=new LinkedHashSet<String>();
        for(var entry:matches)if(entry.chineseName()!=null&&!entry.chineseName().isBlank())names.add(entry.chineseName());
        return names.size()==1?names.iterator().next():null;
    }

    static String apply(DatabaseBuilder db,String profile,String answer){
        return apply(db,profile,answer,Set.of(),Set.of());
    }

    static String apply(DatabaseBuilder db,String profile,String answer,Collection<String> preferredMods,Collection<String> fallbackMods){
        if(answer==null||answer.isBlank()||profile==null)return answer;
        RuntimeNameIndex names;
        try{names=new RuntimeNameIndex(db,profile);}catch(RuntimeException unavailable){return answer;}
        var catalog = new WebCatalogStore(db);
        var cache=new HashMap<String,String>();
        var matcher=PAIR.matcher(answer);
        var builder=new StringBuilder();
        while(matcher.find()){
            String written=matcher.group(1),id=matcher.group(3);
            String canonical=cache.computeIfAbsent(id,key->{
                try{return names.nameOf(key);}catch(Exception failure){return null;}
            });
            String replacement=canonical!=null&&!canonical.isBlank()&&!canonical.equals(written)?canonical+matcher.group(2):matcher.group();
            matcher.appendReplacement(builder,Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(builder);
        String normalized=builder.toString();
        var english=ENGLISH_PAIR.matcher(normalized);var second=new StringBuilder();
        while(english.find()){
            String written=english.group(1),name=english.group(3);
            String canonical=cache.computeIfAbsent("en:"+name,key->resolveEnglish(names,catalog,name,preferredMods,fallbackMods));
            String replacement=canonical!=null&&!canonical.isBlank()&&!canonical.equals(written)?canonical+english.group(2):english.group();
            english.appendReplacement(second,Matcher.quoteReplacement(replacement));
        }
        english.appendTail(second);
        return second.toString();
    }
}
