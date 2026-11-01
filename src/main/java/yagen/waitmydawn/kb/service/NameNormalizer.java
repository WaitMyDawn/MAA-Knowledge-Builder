package yagen.waitmydawn.kb.service;

import java.util.*;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.util.regex.*;

/**
 * 回答里的对象中文名按注册名对应的语言表校正。
 *
 * <p>只处理“名称（注册名）”这种显式配对，避免猜测改写：配对里的注册名能查到本地化名称时，
 * 用语言表名称替换前面的写法（例如把“炼金坩埚”改成“炼金锅”）。查不到就保持原样。
 */
final class NameNormalizer {
    private static final Pattern PAIR=Pattern.compile("([\\u4e00-\\u9fa5]{2,12})(\\s*[（(]\\s*`?([a-z0-9_.-]+:[a-z0-9_./-]+)`?\\s*[）)])");
    /** 中文（English Name）配对：按英文名反查规范中文名。 */
    private static final Pattern ENGLISH_PAIR=Pattern.compile("([\\u4e00-\\u9fa5]{2,12})(\\s*[（(]\\s*([A-Z][A-Za-z0-9_'-]*(?: [A-Za-z0-9_'-]+){0,4})\\s*[）)])");

    private NameNormalizer(){}

    static String apply(DatabaseBuilder db,String profile,String answer){
        if(answer==null||answer.isBlank()||profile==null)return answer;
        RuntimeNameIndex names;
        try{names=new RuntimeNameIndex(db,profile);}catch(RuntimeException unavailable){return answer;}
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
            String written=english.group(1),name=english.group(2);
            String canonical=cache.computeIfAbsent("en:"+name,key->{
                try{var resolution=names.resolve(name,null,null);if(!resolution.status().equals("UNIQUE"))return null;return names.nameOf(resolution.candidates().getFirst().registry());}
                catch(Exception failure){return null;}
            });
            String replacement=canonical!=null&&!canonical.isBlank()&&!canonical.equals(written)?canonical+english.group(2):english.group();
            english.appendReplacement(second,Matcher.quoteReplacement(replacement));
        }
        english.appendTail(second);
        return second.toString();
    }
}
