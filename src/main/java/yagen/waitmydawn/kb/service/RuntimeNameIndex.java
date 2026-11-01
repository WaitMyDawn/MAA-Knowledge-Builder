package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import yagen.waitmydawn.kb.model.*;
import java.sql.*;
import java.text.Normalizer;
import java.util.*;

/** 名称与对象是一对多关系；精确重名返回歧义，不依赖插入或查询顺序选定对象。 */
public final class RuntimeNameIndex {
    private static final ObjectMapper JSON=new ObjectMapper();
    public record Candidate(String category,String recordId,String registry,String kind,String name,String language,String translationKey,String origin){}
    public record Resolution(String status,List<Candidate> candidates){public Resolution{candidates=List.copyOf(candidates);}}
    private final DatabaseBuilder db;private final String profile;
    public RuntimeNameIndex(DatabaseBuilder db,String profile){this.db=db;this.profile=profile;}
    private static String identity(Candidate candidate){return canonicalKind(candidate.kind())+"/"+candidate.registry();}
    private static String canonicalKind(String kind){return Set.of("entity","entity_type","ENTITY").contains(kind)?"ENTITY":kind.toUpperCase(Locale.ROOT);}
    /** 注册名到本地化名称与翻译键；用于确认中文名，避免用别的叫法描述同一对象。 */
    public String nameOf(String id)throws Exception {
        if(profile==null||id==null||!id.contains(":"))return null;
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT language,display_name,translation_key FROM rag_runtime_object_name WHERE profile_id=? AND registry_id=? ORDER BY CASE WHEN language='zh_cn' THEN 0 WHEN language='current' THEN 1 ELSE 2 END LIMIT 1")){
            p.setString(1,profile);p.setString(2,id);
            try(var rows=p.executeQuery()){if(!rows.next())return null;return rows.getString(2);}
        }
    }
    /** 翻译键；与中文名一起返回，便于核对本地化来源。 */
    public String translationKeyOf(String id)throws Exception {
        if(profile==null||id==null||!id.contains(":"))return null;
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT translation_key FROM rag_runtime_object_name WHERE profile_id=? AND registry_id=? ORDER BY CASE WHEN language='zh_cn' THEN 0 WHEN language='current' THEN 1 ELSE 2 END LIMIT 1")){
            p.setString(1,profile);p.setString(2,id);
            try(var rows=p.executeQuery()){if(!rows.next())return null;return rows.getString(1);}
        }
    }    public List<Candidate> list(String mod,String kind,String contains,int offset,int limit)throws Exception {
        if(offset<0||limit<1||limit>100)throw new IllegalArgumentException("Invalid object page");prepare();
        var values=new LinkedHashMap<String,Candidate>();
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT category,record_id,registry_id,kind,display_name,language,translation_key,origin FROM rag_runtime_object_name WHERE profile_id=? ORDER BY registry_id, CASE WHEN language='zh_cn' THEN 0 WHEN language='current' THEN 1 ELSE 2 END,category,record_id")){
            p.setString(1,profile);try(var rows=p.executeQuery()){while(rows.next()){
                var value=new Candidate(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5),rows.getString(6),rows.getString(7),rows.getString(8));
                if(mod!=null&&!mod.isBlank()&&!value.registry().startsWith(mod+":"))continue;
                if(kind!=null&&!kind.isBlank()&&!canonicalKind(value.kind()).equals(canonicalKind(kind)))continue;
                if(contains!=null&&!contains.isBlank()&&!normalize(value.name()).contains(normalize(contains))&&!value.registry().contains(normalize(contains)))continue;
                values.putIfAbsent(identity(value),value);
            }}
        }
        return values.values().stream().skip(offset).limit(limit).toList();
    }
    static void init(Connection c)throws SQLException{try(var s=c.createStatement()){
        s.execute("CREATE TABLE IF NOT EXISTS rag_runtime_object_name(profile_id VARCHAR(64),category VARCHAR(40),record_id VARCHAR(1100),registry_id VARCHAR(500),kind VARCHAR(40),language VARCHAR(16),name_norm VARCHAR(2000),display_name VARCHAR(2000),translation_key VARCHAR(2000),origin VARCHAR(40),PRIMARY KEY(profile_id,category,record_id,language,name_norm))");
        s.execute("CREATE INDEX IF NOT EXISTS idx_runtime_object_name ON rag_runtime_object_name(profile_id,name_norm,kind)");
        s.execute("CREATE TABLE IF NOT EXISTS rag_runtime_name_version(profile_id VARCHAR(64) PRIMARY KEY,version INT)");
    }}
    public static String normalize(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}
    public synchronized void prepare()throws Exception {
        synchronized(RuntimeNameIndex.class){try(var c=db.getConnection()){
            try(var p=c.prepareStatement("SELECT version FROM rag_runtime_name_version WHERE profile_id=?")){p.setString(1,profile);try(var rows=p.executeQuery()){if(rows.next()&&rows.getInt(1)==2)return;}}
            var languages=new HashMap<String,JsonNode>();
            try(var p=c.prepareStatement("SELECT record_data FROM rag_runtime_record WHERE profile_id=? AND category='language'")){p.setString(1,profile);try(var rows=p.executeQuery()){while(rows.next()){var row=JSON.readTree(rows.getString(1));languages.put(row.path("id").asText(),row.path("translations"));}}}
            c.setAutoCommit(false);try {
                try(var p=c.prepareStatement("DELETE FROM rag_runtime_object_name WHERE profile_id=?")){p.setString(1,profile);p.executeUpdate();}
                try(var p=c.prepareStatement("SELECT category,record_id,record_data FROM rag_runtime_record WHERE profile_id=? AND category IN ('registries','potions','effects','enchantments','mod_metadata') ORDER BY category,record_id");var insert=c.prepareStatement("MERGE INTO rag_runtime_object_name KEY(profile_id,category,record_id,language,name_norm) VALUES(?,?,?,?,?,?,?,?,?,?)")){
                    p.setString(1,profile);int pending=0;try(var rows=p.executeQuery()){while(rows.next()){
                        BuildCancellation.check();var row=JSON.readTree(rows.getString(3));if(row.has("error"))continue;String category=rows.getString(1),record=rows.getString(2),id=row.path("id").asText();
                        String kind=category.equals("registries")?row.path("registry").asText().replace("minecraft:",""):category;
                        String prefix=switch(kind){case "item","block"->kind;case "entity_type","entity"->"entity";case "effects"->"effect";case "enchantments"->"enchantment";default->"";};
                        String key=row.path("descriptionId").asText();if(key.isBlank()&&!prefix.isBlank())key=prefix+"."+id.replace(':','.');
                        var names=new LinkedHashMap<String,String>();names.put("registry",id);String display=row.path("displayName").asText(row.path("name").asText());if(!display.isBlank())names.put("current",display);
                        for(var language:languages.entrySet()){String translated=language.getValue().path(key).asText();if(!translated.isBlank())names.put(language.getKey(),translated);}
                        if(category.equals("potions")&&row.path("effects").size()==1) {
                            var effect=row.path("effects").get(0);String effectId=effect.path("effect").asText();String potionKey="item.minecraft.potion.effect."+effectId.substring(effectId.indexOf(':')+1);
                            int level=effect.path("amplifier").asInt()+1;String roman=level>=1&&level<=10?List.of("I","II","III","IV","V","VI","VII","VIII","IX","X").get(level-1):Integer.toString(level);
                            for(var language:languages.entrySet()){String base=language.getValue().path(potionKey).asText();if(base.isBlank())continue;names.put(language.getKey()+"#base",base);names.put(language.getKey()+"#level",base+" "+roman);names.put(language.getKey()+"#compact",base+roman);names.put(language.getKey()+"#number",base+level);}
                        }
                        for(var name:names.entrySet()){String normalized=normalize(name.getValue());if(normalized.isBlank()||normalized.length()>2000)continue;insert.setString(1,profile);insert.setString(2,category);insert.setString(3,record);insert.setString(4,id);insert.setString(5,kind);insert.setString(6,name.getKey().split("#")[0]);insert.setString(7,normalized);insert.setString(8,name.getValue());insert.setString(9,key);insert.setString(10,name.getKey().contains("#")?"EFFECT_LEVEL_LABEL":row.has("descriptionId")?"EXPORTED_TRANSLATION_KEY":"REGISTERED_OBJECT_KEY");insert.addBatch();}
                        if(++pending%200==0){insert.executeBatch();insert.clearBatch();}
                    }}insert.executeBatch();
                }
                try(var p=c.prepareStatement("MERGE INTO rag_runtime_name_version KEY(profile_id) VALUES(?,2)")){p.setString(1,profile);p.executeUpdate();}c.commit();
            }catch(Exception failure){try{c.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
        }}
    }
    private static boolean asciiWord(char ch){return ch>='a'&&ch<='z'||ch>='0'&&ch<='9'||ch=='_';}
    private static final class Trie {final Map<Character,Trie> children=new HashMap<>();final Set<String> names=new LinkedHashSet<>();}
    public List<Resolution> mentions(String question,String kind)throws Exception {
        prepare();String text=normalize(question);var root=new Trie();
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT DISTINCT name_norm FROM rag_runtime_object_name WHERE profile_id=?")){p.setString(1,profile);try(var rows=p.executeQuery()){while(rows.next()){String name=rows.getString(1);if(name.isBlank())continue;var node=root;for(char ch:name.toCharArray())node=node.children.computeIfAbsent(ch,ignored->new Trie());node.names.add(name);}}}
        record Match(int start,int end,String name){}var matches=new ArrayList<Match>();
        for(int start=0;start<text.length();start++){var node=root;for(int end=start;end<text.length();end++){node=node.children.get(text.charAt(end));if(node==null)break;for(String name:node.names){boolean ascii=name.matches("[a-z0-9_ :./-]+");if(ascii&&((start>0&&asciiWord(text.charAt(start-1)))||(end+1<text.length()&&asciiWord(text.charAt(end+1)))))continue;matches.add(new Match(start,end+1,name));}}}
        matches.sort(Comparator.comparingInt((Match match)->match.end()-match.start()).reversed().thenComparingInt(Match::start));
        var selected=new ArrayList<Match>();var result=new ArrayList<Resolution>();
        for(var match:matches){if(selected.stream().anyMatch(old->match.start()<old.end()&&old.start()<match.end()))continue;var found=resolve(match.name(),null,kind);if(found.candidates().isEmpty())continue;selected.add(match);result.add(found);}
        return List.copyOf(result);
    }
    public List<Candidate> suggest(String name,String mod,String kind)throws Exception {
        prepare();String query=normalize(name);var grams=new HashSet<String>();for(int i=0;i<query.length()-1;i++)grams.add(query.substring(i,i+2));
        var scored=new TreeMap<Double,List<Candidate>>(Comparator.reverseOrder());
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT category,record_id,registry_id,kind,display_name,language,translation_key,origin,name_norm FROM rag_runtime_object_name WHERE profile_id=?")) {
            p.setString(1,profile);try(var rows=p.executeQuery()){while(rows.next()) {
                String id=rows.getString(3),actualKind=rows.getString(4);if(mod!=null&&!id.startsWith(mod+":"))continue;if(kind!=null&&!kind.isBlank()&&!kind.equalsIgnoreCase("GENERAL")&&!actualKind.equalsIgnoreCase(kind)&&!(kind.equalsIgnoreCase("ENTITY")&&actualKind.equals("entity_type"))&&!(kind.equalsIgnoreCase("ITEM")&&actualKind.equals("potions")))continue;
                String value=rows.getString(9);var other=new HashSet<String>();for(int i=0;i<value.length()-1;i++)other.add(value.substring(i,i+2));int intersection=0;for(String gram:grams)if(other.contains(gram))intersection++;double score=intersection/(double)Math.max(1,grams.size()+other.size()-intersection);if(value.contains(query)||query.contains(value))score=Math.max(score,0.5);if(score<0.2)continue;
                var candidate=new Candidate(rows.getString(1),rows.getString(2),id,actualKind,rows.getString(5),rows.getString(6),rows.getString(7),rows.getString(8));scored.computeIfAbsent(score,ignored->new ArrayList<>()).add(candidate);
            }}
        }
        var unique=new LinkedHashMap<String,Candidate>();for(var group:scored.values())for(var candidate:group)unique.putIfAbsent(identity(candidate),candidate);return unique.values().stream().limit(5).toList();
    }
    public Resolution resolve(String name,String mod,String kind)throws Exception {
        prepare();var candidates=new LinkedHashMap<String,Candidate>();
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT category,record_id,registry_id,kind,display_name,language,translation_key,origin FROM rag_runtime_object_name WHERE profile_id=? AND name_norm=? ORDER BY category,record_id,language")){
            p.setString(1,profile);p.setString(2,normalize(name));try(var rows=p.executeQuery()){while(rows.next()){
                String registry=rows.getString(3),actualKind=rows.getString(4);if(mod!=null&&!registry.startsWith(mod+":"))continue;if(kind!=null&&!kind.isBlank()&&!kind.equalsIgnoreCase("GENERAL")&&!actualKind.equalsIgnoreCase(kind)&&!(kind.equalsIgnoreCase("ENTITY")&&Set.of("entity_type","entity").contains(actualKind))&&!(kind.equalsIgnoreCase("ITEM")&&actualKind.equals("potions")))continue;
                var value=new Candidate(rows.getString(1),rows.getString(2),registry,actualKind,rows.getString(5),rows.getString(6),rows.getString(7),rows.getString(8));candidates.putIfAbsent(identity(value),value);
            }}
        }
        return new Resolution(candidates.isEmpty()?"NOT_FOUND":candidates.size()==1?"UNIQUE":"AMBIGUOUS",new ArrayList<>(candidates.values()));
    }
}
