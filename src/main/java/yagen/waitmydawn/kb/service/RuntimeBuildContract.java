package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import yagen.waitmydawn.kb.model.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 构建与维护共用准入边界；预检查只验证扫描身份和快照，不解析 JAR 内资源。 */
public final class RuntimeBuildContract {
    public static final String VERSION="runtime-v1";
    private static final ObjectMapper JSON=new ObjectMapper();
    public record Identity(BuildRequest.Source source,ModEntry mod,String hash) {}
    public record Prepared(BuildRequest.Source source,NativeIconPack.Pack pack,List<Identity> identities) {}
    private RuntimeBuildContract() {}
    public static boolean migrated(DatabaseBuilder db){return new SourceLifecycleStore(db).strings("SELECT meta_value FROM rag_build_metadata WHERE name='resource_pipeline'").equals(List.of(VERSION));}
    public static String migrationWarning(DatabaseBuilder db) {
        var store=new SourceLifecycleStore(db);
        if(!store.strings("SELECT meta_value FROM rag_build_metadata WHERE name='resource_pipeline'").equals(List.of(VERSION))
                && (!store.sources().isEmpty() || hasVectors(db)
                || !store.strings("SELECT recipe_id FROM rag_recipe LIMIT 1").isEmpty()))return "旧库尚未迁移到游戏快照构建：请补选完整 MKB-Export，并选择重建；重建需保留希望继续使用的网址与资料来源，历史会话和图片保留。";
        return null;
    }
    private static boolean hasVectors(DatabaseBuilder db){
        var store=new SourceLifecycleStore(db);
        return !store.strings("SELECT table_name FROM information_schema.tables WHERE table_name='rag_vectors'").isEmpty()
                && !store.strings("SELECT chunk_id FROM rag_vectors LIMIT 1").isEmpty();
    }
    public static BuildRequest.Source associated(DatabaseBuilder db) {
        var rows=new SourceLifecycleStore(db).strings("SELECT s.location FROM rag_knowledge_source s JOIN rag_runtime_snapshot r ON r.source_id=s.id ORDER BY s.id");
        if(rows.size()!=1)throw new IllegalStateException("请先完成游戏快照迁移或重建；当前库没有唯一关联的完整快照。");
        return BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,rows.getFirst());
    }
    public static Prepared prepare(BuildRequest request,DatabaseBuilder db,Map<Path,JarScanCache.Snapshot> inMemory)throws IOException {
        return prepare(request,db,inMemory,new ScanEnvironmentStore(db));
    }
    static Prepared prepare(BuildRequest request,DatabaseBuilder db,Map<Path,JarScanCache.Snapshot> inMemory,ScanEnvironmentStore environments)throws IOException {
        if(request.mode()==BuildRequest.Mode.APPEND && migrationWarning(db)!=null)throw new IllegalArgumentException(migrationWarning(db));
        var pack=ExportSnapshotPreflight.requireBuildSnapshot(request,null,List.of());validateComplete(pack);
        var source=request.sources().stream().filter(s->s.kind()==BuildRequest.Kind.DOCUMENT_DIRECTORY&&Path.of(s.location()).toAbsolutePath().normalize().equals(pack.root())).findFirst().orElseThrow();
        var identities=new ArrayList<Identity>();var seen=new HashMap<String,String>();
        for(var jarSource:request.sources())if(jarSource.kind()==BuildRequest.Kind.JAR_DIRECTORY) {
            Path folder=Path.of(jarSource.location()).toAbsolutePath().normalize();
            var snapshot=inMemory.get(folder);if(snapshot==null)snapshot=new JarScanCache(folder).snapshot();
            if(snapshot==null||snapshot.environment()==null)throw new IllegalArgumentException("请先 Scan 扫描并确认环境："+folder);
            var expected=environments.forFolder(folder);
            if(expected!=null&&!expected.equals(snapshot.environment()))throw new IllegalArgumentException("Scan 记录与已确认环境不一致，请重新 Scan："+folder);
            var items=new HashMap<Path,JarScanCache.Item>();
            for(var item:snapshot.items()) {
                Path path=ArchivePaths.resolve(folder,item.relativePath().replace('\\','/'));
                if(items.putIfAbsent(path,item)!=null)throw new IllegalArgumentException("Scan 记录包含重复文件");
            }
            var jars=new JarScannerService().scan(folder);if(jars.isEmpty()||jars.size()!=items.size())throw new IllegalArgumentException("模组目录清单已变化，请重新 Scan："+folder);
            var mods=new ArrayList<ModEntry>();
            for(Path jar:jars) {
                jar=jar.toAbsolutePath().normalize();var item=items.get(jar);var hashes=JarScanCache.hashes(jar);
                if(item==null||!hashes.sha512().equals(item.sha512()))throw new IllegalArgumentException("JAR 已变化，请重新 Scan 并重新导出快照："+jar.getFileName());
                if(item.mod()==null)continue;
                var mod=JSON.convertValue(item.mod(),ModEntry.class);mod.setJarPath(jar.toString());mod.setJarFileName(jar.getFileName().toString());
                String previous=seen.putIfAbsent(mod.getModId(),hashes.sha256());if(previous!=null&&!previous.equals(hashes.sha256()))throw new IllegalArgumentException("存在不同内容的同名模组："+mod.getModId());
                mods.add(mod);identities.add(new Identity(jarSource,mod,hashes.sha256()));
            }
            ExportSnapshotPreflight.inspect(pack,snapshot.environment(),mods).requireReady();
        }
        if(request.mode()==BuildRequest.Mode.APPEND)requireCompatibleAppend(db,pack);
        return new Prepared(source,pack,List.copyOf(identities));
    }
    static void verifyUnchanged(Prepared prepared,BuildRequest request,DatabaseBuilder db,Map<Path,JarScanCache.Snapshot> scans,ScanEnvironmentStore environments)throws IOException {
        var current=prepare(request,db,scans,environments);
        if(!prepared.pack().manifestHash().equals(current.pack().manifestHash()))throw new IOException("构建期间游戏快照变化，请重新构建");
        var before=prepared.identities().stream().map(i->i.mod().getJarPath()+"|"+i.hash()).sorted().toList();
        var after=current.identities().stream().map(i->i.mod().getJarPath()+"|"+i.hash()).sorted().toList();
        if(!before.equals(after))throw new IOException("构建期间 JAR 身份变化，请重新 Scan 和导出快照");
    }
    public static void validateComplete(NativeIconPack.Pack pack)throws IOException {
        ExportSnapshotPreflight.inspect(pack,null).requireReady();var data=RuntimeSnapshotData.read(pack);
        var declared=new TreeMap<String,String>();pack.environment().path("mods").forEach(m->declared.put(m.path("id").asText(),m.path("version").asText()));
        var actual=new TreeMap<String,String>();data.categories().get("mod_metadata").path("records").forEach(m->actual.put(m.path("id").asText(),m.path("version").asText()));
        if(!actual.equals(declared)||!actual.containsKey("minecraft"))throw new IOException("快照模组元数据与环境清单不一致，或缺少 Minecraft 身份");
        var items=new TreeSet<String>();for(var row:data.categories().get("registries").path("records"))if(row.path("registry").asText().equals("item"))items.add(row.path("id").asText());
        if(!items.remove("minecraft:air"))throw new IOException("快照缺少完整物品注册表");
        var icons=new TreeSet<String>();for(var icon:pack.icons())if(icon.variant().equals("default"))icons.add(icon.itemId());
        if(!items.equals(icons))throw new IOException("全量快照的默认图标记录与物品注册表不一致，请使用 /mkb_export all 重新导出");
        var languages=new HashSet<String>();for(var row:data.categories().get("language").path("records"))languages.add(row.path("id").asText());
        if(!languages.equals(Set.of("en_us","zh_cn","current")))throw new IOException("快照缺少当前语言或中英文映射");
    }
    private static void requireCompatibleAppend(DatabaseBuilder db,NativeIconPack.Pack pack)throws IOException {
        var rows=new SourceLifecycleStore(db).strings("SELECT p.manifest_json FROM rag_runtime_snapshot s JOIN rag_item_icon_profile p ON p.id=s.profile_id");
        if(rows.size()>1)throw new IllegalArgumentException("旧库含多个游戏环境，请选择一个完整快照重建。");
        if(rows.isEmpty())return;
        var old=JSON.readTree(rows.getFirst()).path("environment");
        if(!old.path("minecraftVersion").equals(pack.environment().path("minecraftVersion"))||!old.path("loader").equals(pack.environment().path("loader")))throw new IllegalArgumentException("游戏版本或加载器变化，请使用完整重建。");
        var current=new HashMap<String,JsonNode>();pack.environment().path("mods").forEach(mod->current.put(mod.path("id").asText(),mod));
        for(var mod:old.path("mods")) {
            String id=mod.path("id").asText();if(id.equals("mkb_export"))continue;var next=current.get(id);
            if(next==null||!mod.path("version").equals(next.path("version"))||mod.hasNonNull("sha256")&&next.hasNonNull("sha256")&&!mod.path("sha256").equals(next.path("sha256")))throw new IllegalArgumentException("已有模组移除、版本或文件内容变化，请完整重建："+id);
        }
    }
    public static void mark(DatabaseBuilder db,String source) {
        var store=new SourceLifecycleStore(db);store.execute("MERGE INTO rag_build_metadata KEY(name) VALUES('resource_pipeline',?)",VERSION);
        store.execute("MERGE INTO rag_build_metadata KEY(name) VALUES('game_snapshot_source',?)",source);
    }
    static void removeLegacyResources(DatabaseBuilder db) {
        var store=new SourceLifecycleStore(db);
        for(String table:List.of("rag_recipe","rag_texture_cache","rag_item_tag_file","rag_item_name","rag_item_static_icon","rag_recipe_resource_import"))store.execute("DELETE FROM "+table);
        store.execute("DELETE FROM rag_entity_registry WHERE source_path IS NULL OR source_path NOT LIKE 'mkb-runtime:%'");
        store.execute("DELETE FROM rag_vectors WHERE chunk_type IN ('recipe','lang_items','description')");
    }
}
