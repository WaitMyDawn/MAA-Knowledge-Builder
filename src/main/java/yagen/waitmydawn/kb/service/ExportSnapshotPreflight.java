package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import yagen.waitmydawn.kb.model.GameEnvironment;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 完整知识快照的准入规则与图标兼容导入分离，供资源迁移后的构建路径强制执行。 */
public final class ExportSnapshotPreflight {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public static final List<String> REQUIRED=List.of("mod_metadata","registries","language","item_icons","recipes","tags","loot_tables","loot_modifiers");
    private static final Set<String> CORE=Set.of("mod_metadata","registries","language","item_icons","recipes","tags");
    public record Assessment(boolean snapshot,List<String> blockers,List<String> warnings) {
        public Assessment {blockers=List.copyOf(blockers);warnings=List.copyOf(warnings);}
        public boolean ready(){return snapshot && blockers.isEmpty();}
        public void requireReady(){if(!ready())throw new IllegalArgumentException("完整 MKB-Export 快照不可用于 Build："+String.join("；",blockers));}
        public String summary(){return ready()?"完整知识快照校验通过":"完整知识快照尚不可用："+String.join("；",blockers);}
    }
    private ExportSnapshotPreflight() {}

    public static NativeIconPack.Pack requireBuildSnapshot(yagen.waitmydawn.kb.model.BuildRequest request,GameEnvironment expected,
                                                          Collection<yagen.waitmydawn.kb.model.ModEntry> mods)throws IOException {
        var candidates=new ArrayList<Path>();
        for(var source:request.sources())if(source.kind()==yagen.waitmydawn.kb.model.BuildRequest.Kind.DOCUMENT_DIRECTORY
                && NativeIconPack.detected(Path.of(source.location())))candidates.add(Path.of(source.location()));
        if(candidates.size()!=1)throw new IllegalArgumentException("请在文件入口明确选择一个完整 MKB-Export 快照目录，当前数量："+candidates.size());
        var pack=NativeIconPack.read(candidates.getFirst());inspect(pack,expected,mods).requireReady();return pack;
    }

    public static Assessment inspect(NativeIconPack.Pack pack,GameEnvironment expected,Collection<yagen.waitmydawn.kb.model.ModEntry> mods)throws IOException {
        var base=inspect(pack,expected);var blockers=new ArrayList<>(base.blockers());var warnings=new ArrayList<>(base.warnings());
        var exported=new HashMap<String,JsonNode>();pack.environment().path("mods").forEach(mod->exported.put(mod.path("id").asText(),mod));
        var hashes=new HashMap<Path,String>();
        for(var mod:mods) {
            if(mod.getModId().equals("minecraft"))continue;
            var found=exported.get(mod.getModId());
            if(found==null){blockers.add("导出环境缺少模组："+mod.getModId());continue;}
            if(!Objects.equals(mod.getVersion(),found.path("version").asText()))blockers.add("模组版本不一致："+mod.getModId());
            if(found.hasNonNull("sha256")&&mod.getJarPath()!=null) {
                Path jar=Path.of(mod.getJarPath()).toAbsolutePath().normalize();String hash=hashes.get(jar);
                if(hash==null){hash=ArtifactFingerprint.sha256(jar);hashes.put(jar,hash);}
                if(!hash.equals(found.path("sha256").asText()))blockers.add("模组文件内容不一致："+mod.getModId());
            } else warnings.add("模组缺少可比较的文件哈希："+mod.getModId());
        }
        return new Assessment(base.snapshot(),blockers,warnings);
    }

    public static Assessment inspect(NativeIconPack.Pack pack,GameEnvironment expected) throws IOException {
        Path descriptor=ArchivePaths.resolve(pack.root(),"snapshot.json");
        if(!Files.exists(descriptor,LinkOption.NOFOLLOW_LINKS))return new Assessment(false,List.of("旧版图标包缺少 snapshot.json"),List.of());
        var tree=read(descriptor,1024*1024);
        if(!"mkb-snapshot".equals(tree.path("format").asText())||!tree.path("schemaVersion").isIntegralNumber()||tree.path("schemaVersion").asInt()!=1)throw new IOException("不支持的知识快照协议");
        for(String key:List.of("clientFingerprint","serverFingerprint")) {
            if(key.equals("serverFingerprint")&&"CLIENT_ONLY".equals(tree.path("captureMode").asText()))continue;
            if(!tree.path(key).asText().matches("[0-9a-f]{64}"))throw new IOException("快照指纹无效："+key);
        }
        if(!pack.environmentHash().equals(tree.path("clientFingerprint").asText()))throw new IOException("客户端指纹与图标环境不一致");
        if(!Set.of("SINGLEPLAYER","CLIENT_ONLY").contains(tree.path("captureMode").asText())||!Set.of("ALL","SELECTION").contains(tree.path("coverage").asText()))throw new IOException("快照模式或覆盖范围无效");
        if("SINGLEPLAYER".equals(tree.path("captureMode").asText())
                && (!tree.path("serverSession").asText().matches("[0-9a-f-]{36}")||!tree.path("serverGeneration").isIntegralNumber()||!tree.path("serverGeneration").canConvertToLong()||tree.path("serverGeneration").asLong()<0))throw new IOException("服务端会话或重载代次无效");
        var blockers=new ArrayList<String>();var warnings=new ArrayList<String>();
        if(!"SINGLEPLAYER".equals(tree.path("captureMode").asText()))blockers.add("需要单人世界服务端数据");
        if(!"ALL".equals(tree.path("coverage").asText()))blockers.add("仅导出了部分对象");
        if(!"GAME_GUI".equals(pack.origin()))blockers.add("需要真实游戏导出产物");
        if(expected!=null && (!expected.minecraftVersion().equals(pack.minecraftVersion())||!expected.loader().equals(pack.loader())))blockers.add("导出环境与 Scan 确认的版本或加载器不匹配");
        var categories=tree.path("categories");if(!categories.isObject()||categories.size()>64)throw new IOException("快照类别声明无效");
        long bytes=0;
        var fields=categories.fields();
        while(fields.hasNext()) {
            var field=fields.next();String name=field.getKey();var value=field.getValue();
            if(!name.matches("[a-z_]{1,64}")||!value.isObject())throw new IOException("快照类别格式无效");
            long accounted=0;
            for(String counter:List.of("total","succeeded","transparent","unsupported","failed")) {
                var number=value.path(counter);if(!number.isIntegralNumber()||!number.canConvertToInt()||number.asInt()<0)throw new IOException("类别计数无效："+name);
                if(!counter.equals("total"))accounted+=number.asInt();
            }
            if(accounted!=value.path("total").asInt())throw new IOException("类别计数不平衡："+name);
            String status=value.path("status").asText();
            if(!Set.of("COMPLETE","COMPLETE_WITH_ERRORS","NOT_IMPLEMENTED","FAILED").contains(status))throw new IOException("类别状态无效："+name);
            boolean completed=status.equals("COMPLETE")||status.equals("COMPLETE_WITH_ERRORS");
            if(status.equals("COMPLETE") && (value.path("failed").asInt()!=0||value.path("unsupported").asInt()!=0))throw new IOException("完整类别含未完成项目："+name);
            if(completed) {
                String file=value.path("file").asText(),hash=value.path("sha256").asText();
                if(!hash.matches("[0-9a-f]{64}")||!(name.equals("item_icons")?file.equals("manifest.json"):file.equals("data/"+name+".json")))throw new IOException("类别文件映射无效："+name);
                Path payload=ArchivePaths.resolve(pack.root(),file);
                if(!Files.isRegularFile(payload,LinkOption.NOFOLLOW_LINKS)||Files.size(payload)>64L*1024*1024)throw new IOException("类别文件缺失或超限："+name);
                bytes+=Files.size(payload);if(bytes>256L*1024*1024)throw new IOException("知识快照文本总量超限");
                if(!hash.equals(ArtifactFingerprint.sha256(payload)))throw new IOException("类别文件哈希不匹配："+name);
                if(!name.equals("item_icons")) {
                    var document=read(payload,64*1024*1024);
                    if(Set.of("mod_metadata","registries","language","recipes","tags","loot_tables","loot_modifiers","entity_icons").contains(name))RuntimeSnapshotData.validate(name,document,value,pack.root());
                }
                else verifyIcons(pack,value);
            } else if(value.hasNonNull("file")||value.hasNonNull("sha256")||value.path("total").asInt()!=0)throw new IOException("未完成类别不能声明有效载荷："+name);
            if(CORE.contains(name) && !completed)blockers.add(name+"："+status);
            else if(!completed)warnings.add(name+"："+status);
            if(status.equals("COMPLETE_WITH_ERRORS")) {
                if(CORE.contains(name)&&!name.equals("item_icons"))blockers.add(name+" 存在未完成项目");
                warnings.add(name+"：失败 "+value.path("failed").asInt()+"，不支持 "+value.path("unsupported").asInt());
            }
        }
        for(String name:REQUIRED)if(!categories.has(name))blockers.add("缺少类别："+name);
        return new Assessment(true,blockers,warnings);
    }
    private static void verifyIcons(NativeIconPack.Pack pack,JsonNode category)throws IOException {
        int ok=0,transparent=0,unsupported=0,failed=0;
        for(var icon:pack.icons())switch(icon.status()){case "OK"->ok++;case "TRANSPARENT"->transparent++;case "UNSUPPORTED"->unsupported++;default->failed++;}
        if(category.path("total").asInt()!=pack.icons().size()||category.path("succeeded").asInt()!=ok||category.path("transparent").asInt()!=transparent||category.path("unsupported").asInt()!=unsupported||category.path("failed").asInt()!=failed)throw new IOException("图标统计与清单不一致");
    }
    private static JsonNode read(Path file,int limit)throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>limit)throw new IOException("快照文件缺失或超限");
        try(var input=Files.newInputStream(file)){byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("快照大小超限");var node=JSON.readTree(bytes);if(node==null||!node.isObject())throw new IOException("快照文件必须是 JSON 对象");return node;}
    }
}
