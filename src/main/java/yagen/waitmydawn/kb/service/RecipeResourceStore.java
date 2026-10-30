package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import yagen.waitmydawn.kb.model.*;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.Pattern;

/** JAR 中的材料标签、名称和可保守展开的单层平面模型；不执行加载器或配方脚本。 */
public final class RecipeResourceStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INDEX_VERSION="r3-v2";
    private static final Pattern TAG = Pattern.compile("data/([^/]+)/tags/items?/(.+)\\.json");
    private static final Pattern MODEL = Pattern.compile("assets/([^/]+)/models/(.+)\\.json");
    private final DatabaseBuilder db;
    public RecipeResourceStore(DatabaseBuilder db) { this.db = db; }
    public record Imported(int tags,int names,int flatIcons,List<String> warnings){
        public Imported(int tags,int names,int flatIcons){this(tags,names,flatIcons,List.of());}
        public Imported{warnings=List.copyOf(warnings);}
    }
    public record TagFile(String source, JsonNode json) {}
    public record TagResult(List<String> items, String problem) { public boolean resolved() { return problem == null; } }
    public static void init(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS rag_item_tag_file(id VARCHAR(64) PRIMARY KEY,tag_id VARCHAR(500) NOT NULL,source_mod VARCHAR(255) NOT NULL,source_path VARCHAR(1000) NOT NULL,tag_json CLOB NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_item_name(id VARCHAR(64) PRIMARY KEY,item_id VARCHAR(500) NOT NULL,language VARCHAR(16) NOT NULL,display_name VARCHAR(1000) NOT NULL,source_mod VARCHAR(255) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_item_static_icon(id VARCHAR(500) PRIMARY KEY,image_id VARCHAR(64),model_hash VARCHAR(64) NOT NULL,source_mod VARCHAR(255) NOT NULL,status VARCHAR(32) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_recipe_resource_import(id VARCHAR(255) PRIMARY KEY,content_hash VARCHAR(64) NOT NULL,index_version VARCHAR(32) NOT NULL)");
        }
    }
    public Imported importJar(Path path, String sourceMod, Path dataDir) throws Exception {
        String jarHash=JarBuildIndex.hash(path);
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT content_hash,index_version FROM rag_recipe_resource_import WHERE id=?")) {
            p.setString(1,sourceMod);try(var r=p.executeQuery()){if(r.next()&&r.getString(1).equals(jarHash)&&r.getString(2).equals(INDEX_VERSION))return new Imported(0,0,0);}
        }
        int tags = 0, names = 0, icons = 0; long textBytes = 0;var warnings=new ArrayList<String>();
        var models = new TreeMap<String, JsonNode>();
        try (var jar = new JarFile(path.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                cancelled(); var entry = entries.nextElement(); String name = entry.getName();
                if (entry.isDirectory()) continue;
                var tag = TAG.matcher(name); var model = MODEL.matcher(name);
                if (tag.matches()) {
                    String id = RecipePlan.resourceId(tag.group(1) + ":" + tag.group(2));
                    String text = read(jar, entry, 2 * 1024 * 1024); textBytes += text.length();
                    var json = JSON.readTree(text);
                    if (!json.isObject() || !json.path("values").isArray()) throw new IOException("材料标签格式无效：" + name);
                    try (var c = db.getConnection(); var p = c.prepareStatement("MERGE INTO rag_item_tag_file KEY(id) VALUES(?,?,?,?,?)")) {
                        p.setString(1, BuildRequest.fingerprint(sourceMod + "\n" + name)); p.setString(2, id); p.setString(3, sourceMod); p.setString(4, name); p.setString(5, text); p.executeUpdate();
                    }
                    if (++tags > 20_000) throw new IOException("标签文件数量超限");
                } else if (model.matches()) {
                    String text = read(jar, entry, 512 * 1024); textBytes += text.length();
                    models.put(RecipePlan.resourceId(model.group(1) + ":" + model.group(2)), JSON.readTree(text));
                    if (models.size() > 30_000) throw new IOException("模型数量超限");
                } else if (name.matches("assets/[^/]+/lang/(zh_cn|en_us)\\.json")) {
                    String text = read(jar, entry, 16 * 1024 * 1024); textBytes += text.length();
                    String language = name.substring(name.lastIndexOf('/') + 1, name.length() - 5);
                    try{names+=importLanguage(text,language,sourceMod);}
                    catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid){
                        String position=invalid instanceof com.fasterxml.jackson.core.JsonProcessingException json&&json.getLocation()!=null?"（行 "+json.getLocation().getLineNr()+"，列 "+json.getLocation().getColumnNr()+"）":"";
                        warnings.add(sourceMod+"：跳过无效语言文件 "+name+position+"；其他语言继续导入");
                    }
                }
                if (textBytes > 128L * 1024 * 1024) throw new IOException("配方资源文本总量超限");
            }
            var assets = new ImageAssetStore(dataDir); var content = new KnowledgeContentStore(db);
            for (var entry : models.entrySet()) {
                cancelled(); int split = entry.getKey().indexOf(":item/"); if (split < 0) continue;
                var merged = flatModel(entry.getKey(), models, new HashSet<>()); if (merged == null) continue;
                var textures = merged.path("textures"); String texture = textures.path("layer0").asText("");
                var variables = new HashSet<String>();
                while (texture.startsWith("#") && variables.add(texture) && variables.size() < 32) texture = textures.path(texture.substring(1)).asText("");
                if (texture.isBlank() || texture.startsWith("#")) continue;
                if (!texture.contains(":")) texture = entry.getKey().substring(0, split) + ":" + texture;
                try { RecipePlan.resourceId(texture); } catch (IllegalArgumentException e) { continue; }
                String file = "assets/" + texture.replace(':', '/') + ".png";
                file = file.replaceFirst("^assets/([^/]+)/", "assets/$1/textures/");
                if (jar.getJarEntry(file + ".mcmeta") != null) continue;
                var png = jar.getJarEntry(file); if (png == null) continue;
                byte[] bytes; try (var input = jar.getInputStream(png)) { bytes = input.readNBytes(8 * 1024 * 1024 + 1); }
                if (bytes.length > 8 * 1024 * 1024) continue;
                int width, height;
                try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                    var readers = ImageIO.getImageReaders(input); if (!readers.hasNext()) continue; var reader = readers.next();
                    try { reader.setInput(input); width = reader.getWidth(0); height = reader.getHeight(0); if (!reader.getFormatName().equalsIgnoreCase("png")) continue; }
                    finally { reader.dispose(); }
                }
                if (width != height || width < 1 || width > 512) continue;
                var asset = assets.put(bytes); content.saveImage(asset);
                String id = entry.getKey().substring(0, split) + ":" + entry.getKey().substring(split + 6);
                String modelHash = BuildRequest.fingerprint(merged.toString() + "\n" + asset.id());
                boolean conflict = false;
                try (var c = db.getConnection(); var p = c.prepareStatement("SELECT model_hash,status FROM rag_item_static_icon WHERE id=?")) {
                    p.setString(1, id); try (var r = p.executeQuery()) { if (r.next()) conflict = !r.getString(1).equals(modelHash) || r.getString(2).equals("CONFLICT"); }
                }
                try (var c = db.getConnection(); var p = c.prepareStatement("MERGE INTO rag_item_static_icon KEY(id) VALUES(?,?,?,?,?)")) {
                    p.setString(1, id); p.setString(2, conflict ? null : asset.id()); p.setString(3, modelHash); p.setString(4, sourceMod); p.setString(5, conflict ? "CONFLICT" : "FLAT_MODEL"); p.executeUpdate();
                }
                icons++;
            }
        }
        try(var c=db.getConnection();var p=c.prepareStatement("MERGE INTO rag_recipe_resource_import KEY(id) VALUES(?,?,?)")){p.setString(1,sourceMod);p.setString(2,jarHash);p.setString(3,INDEX_VERSION);p.executeUpdate();}
        return new Imported(tags,names,icons,warnings);
    }
    /** 附加已取得的语言资源，不创建配方或文本向量；命名空间来自完整翻译键。 */
    public int importLanguage(String json,String language,String sourceMod) throws Exception {
        if(!Set.of("zh_cn","en_us").contains(language)||json.length()>16*1024*1024)throw new IllegalArgumentException("语言或文本大小无效");
        var root=JSON.readTree(json);if(root==null||!root.isObject())throw new IllegalArgumentException("语言文件必须为对象");
        var values=new LinkedHashMap<String,String>();
        for(String prefix:List.of("block.","item.")) {
            var fields=root.fields();while(fields.hasNext()) {
                var field=fields.next();if(!field.getKey().startsWith(prefix)||!field.getValue().isTextual())continue;
                var parts=field.getKey().split("\\.",3);if(parts.length!=3)continue;
                String id=parts[1]+":"+parts[2];try{RecipePlan.resourceId(id);}catch(IllegalArgumentException e){continue;}
                String value=field.getValue().asText();if(!value.isBlank()&&value.length()<=1000)values.put(id,value);
            }
        }
        try(var c=db.getConnection();var p=c.prepareStatement("MERGE INTO rag_item_name KEY(id) VALUES(?,?,?,?,?)")) {
            for(var entry:values.entrySet()){p.setString(1,BuildRequest.fingerprint(sourceMod+"\n"+language+"\n"+entry.getKey()));p.setString(2,entry.getKey());p.setString(3,language);p.setString(4,entry.getValue());p.setString(5,sourceMod);p.addBatch();}p.executeBatch();
        }
        return values.size();
    }
    public List<String> findItemIds(String question) {
        record Hit(String id,int start,int end) {}
        var hits=new ArrayList<Hit>();
        var ids=java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+").matcher(question);
        while(ids.find())hits.add(new Hit(ids.group(),ids.start(),ids.end()));
        try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT DISTINCT item_id,display_name FROM "+RuntimeKnowledgeStore.nameRelation(db))) {
            while(r.next()) {
                String name=r.getString(2);boolean han=name.codePoints().anyMatch(cp->Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN);
                if(name.length()<2&&(!han||!question.toLowerCase(Locale.ROOT).matches("(?s).*(合成|制作|配方|锻造|冶炼|加工|craft|recipe).*")))continue;
                var pattern=java.util.regex.Pattern.compile(han?java.util.regex.Pattern.quote(name):"(?<![a-z0-9_])"+java.util.regex.Pattern.quote(name)+"(?![a-z0-9_])",java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.UNICODE_CASE);
                var matcher=pattern.matcher(question);while(matcher.find())hits.add(new Hit(r.getString(1),matcher.start(),matcher.end()));
            }
        }catch(SQLException e){throw new IllegalStateException("配方名称索引读取失败",e);}
        hits.sort(Comparator.<Hit>comparingInt(h->h.end()-h.start()).reversed().thenComparing(Hit::id));
        var chosen=new ArrayList<Hit>();for(var hit:hits)if(chosen.stream().noneMatch(old->hit.start()<old.end()&&hit.end()>old.start()&&(hit.start()!=old.start()||hit.end()!=old.end()||hit.id().equals(old.id()))))chosen.add(hit);
        return chosen.stream().map(Hit::id).distinct().limit(16).toList();
    }
    private static JsonNode flatModel(String id, Map<String, JsonNode> models, Set<String> seen) {
        if (!seen.add(id) || seen.size() > 32) return null;
        var model = models.get(id); if (model == null || !model.isObject()) return null;
        for (String field : List.of("loader", "elements", "overrides", "display", "render_type")) if (model.has(field)) return null;
        String parent = model.path("parent").asText(""); if (!parent.contains(":")) parent = "minecraft:" + parent;
        JsonNode merged;
        if (Set.of("minecraft:item/generated", "minecraft:item/handheld", "minecraft:builtin/generated").contains(parent)) merged = JSON.createObjectNode();
        else { merged = flatModel(parent, models, seen); if (merged == null) return null; }
        var copy = ((com.fasterxml.jackson.databind.node.ObjectNode) merged).deepCopy();
        var textures = copy.has("textures") ? ((com.fasterxml.jackson.databind.node.ObjectNode) copy.path("textures")).deepCopy() : JSON.createObjectNode();
        if (model.path("textures").isObject()) textures.setAll((com.fasterxml.jackson.databind.node.ObjectNode) model.path("textures"));
        var fields = textures.fieldNames(); while (fields.hasNext()) { String field = fields.next(); if (field.startsWith("layer") && !field.equals("layer0")) return null; }
        if (!textures.path("layer0").isTextual()) return null;
        copy.set("textures", textures); return copy;
    }
    private static String read(JarFile jar, JarEntry entry, int limit) throws IOException {
        byte[] bytes; try (var input = jar.getInputStream(entry)) { bytes = input.readNBytes(limit + 1); }
        if (bytes.length > limit) throw new IOException("JAR 配方资源过大：" + entry.getName());
        return new String(bytes, StandardCharsets.UTF_8);
    }
    public static Map<String, List<TagFile>> tags(Connection c) throws Exception {
        String runtime=RuntimeKnowledgeStore.active(c);if(runtime!=null)return RuntimeKnowledgeStore.tags(c,runtime);
        var result = new TreeMap<String, List<TagFile>>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT tag_id,source_mod,tag_json FROM rag_item_tag_file ORDER BY id")) {
            while (r.next()) result.computeIfAbsent(r.getString(1), key -> new ArrayList<>()).add(new TagFile(r.getString(2), JSON.readTree(r.getString(3))));
        }
        return result;
    }
    public static TagResult resolveTag(String id, Map<String, List<TagFile>> tags) {
        try { var items = new TreeSet<String>(); expand(id, tags, new HashSet<>(), items, new int[1]); return new TagResult(List.copyOf(items), items.isEmpty() ? "标签没有可确认的 JAR 候选" : null); }
        catch (IllegalArgumentException e) { return new TagResult(List.of(), e.getMessage()); }
    }
    private static void expand(String id, Map<String, List<TagFile>> tags, Set<String> stack, Set<String> items, int[] steps) {
        if (!stack.add(id) || stack.size() > 32 || ++steps[0] > 2048) throw new IllegalArgumentException("标签循环或展开预算超限：#" + id);
        try {
            var files = tags.get(id); if (files == null || files.isEmpty()) throw new IllegalArgumentException("缺少标签定义：#" + id);
            if (files.size() > 1 && files.stream().anyMatch(f -> f.json().path("replace").asBoolean(false))) throw new IllegalArgumentException("标签有 replace 冲突，JAR 加载顺序未知：#" + id);
            for (var file : files) {
                var fields = file.json().fieldNames(); while (fields.hasNext()) if (!Set.of("replace", "values").contains(fields.next())) throw new IllegalArgumentException("标签含未适配规则：#" + id);
                if (file.json().path("values").size() > 10_000) throw new IllegalArgumentException("标签候选过多：#" + id);
                for (var value : file.json().path("values")) {
                    String text = value.isTextual() ? value.asText() : value.path("id").asText("");
                    boolean optional = value.isObject() && value.has("required") && !value.path("required").asBoolean(true);
                    if (text.startsWith("#")) {
                        String child = RecipePlan.resourceId(text.substring(1)); if (optional && !tags.containsKey(child)) continue;
                        expand(child, tags, stack, items, steps);
                    } else items.add(RecipePlan.resourceId(text));
                    if (items.size() > 2048) throw new IllegalArgumentException("标签展开超过 2048 项：#" + id);
                }
            }
        } finally { stack.remove(id); }
    }
    private static void cancelled() { BuildCancellation.check(); }
}
