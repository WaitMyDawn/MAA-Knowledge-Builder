package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 实机图标协议 v1/v2：精确注册名与状态、环境隔离及有界资源校验。 */
public final class NativeIconPack {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public static final String FORMAT="mkb-export";
    private static final long MAX_MANIFEST=16L*1024*1024,MAX_ICON=8L*1024*1024,MAX_TOTAL=512L*1024*1024;
    public record Icon(String itemId,String status,String file,String sha256,String error,String variant,JsonNode state) {}
    public record Pack(Path root,String manifestHash,String environmentHash,String origin,String exporterVersion,
                       String minecraftVersion,String loader,JsonNode environment,JsonNode rendering,
                       String manifestJson,List<Icon> icons) {}
    private NativeIconPack() {}

    public static boolean detected(Path root) {
        boolean namedPack=root.getFileName()!=null && root.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("mkb-export");
        if(!Files.exists(root.resolve("manifest.json"),LinkOption.NOFOLLOW_LINKS)) {
            if(namedPack)throw new IllegalArgumentException("MKB-Export 目录缺少 manifest.json，不能作为普通资料导入");
            return false;
        }
        try {
            rejectSensitive(root);Path file=ArchivePaths.resolve(root,"manifest.json");
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>MAX_MANIFEST)throw new IOException("导出清单类型或大小无效");
            byte[] bytes;try(var input=Files.newInputStream(file)){bytes=input.readNBytes((int)MAX_MANIFEST+1);}
            if(bytes.length>MAX_MANIFEST)throw new IOException("清单大小超限");
            var tree=JSON.readTree(bytes);
            return namedPack || (tree!=null && FORMAT.equals(tree.path("format").asText()));
        } catch(IOException e){throw new IllegalArgumentException("manifest.json 无法安全识别："+e.getMessage(),e);}
    }

    public static Pack read(Path selected) throws IOException {
        rejectSensitive(selected);
        Path root=selected.toAbsolutePath().normalize();ArchivePaths.resolve(root,"manifest.json");
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) throw new IOException("导出包必须是普通目录");
        Path manifest=ArchivePaths.resolve(root,"manifest.json");
        if(!Files.isRegularFile(manifest,LinkOption.NOFOLLOW_LINKS) || Files.size(manifest)>MAX_MANIFEST) throw new IOException("导出清单不存在或超过 16 MiB");
        JsonNode tree;
        try(var input=Files.newInputStream(manifest)) {
            byte[] bytes=input.readNBytes((int)MAX_MANIFEST+1);if(bytes.length>MAX_MANIFEST) throw new IOException("导出清单过大");
            tree=JSON.readTree(bytes);
        }
        fields(tree,"format","schemaVersion","origin","exportedAt","exporter","environment","rendering","icons");
        if(!FORMAT.equals(text(tree,"format",40)) || !tree.path("schemaVersion").isIntegralNumber() || !tree.path("schemaVersion").canConvertToInt() || !Set.of(1,2).contains(tree.path("schemaVersion").asInt())) throw new IOException("不支持的 MKB-Export 格式版本");
        int schema=tree.path("schemaVersion").asInt();
        if(tree.has("exportedAt"))text(tree,"exportedAt",128);
        String origin=text(tree,"origin",40);if(!Set.of("GAME_GUI","FIXTURE").contains(origin)) throw new IOException("origin 必须为 GAME_GUI 或 FIXTURE");
        var exporter=tree.path("exporter");fields(exporter,"id","version");
        if(!"mkb_export".equals(text(exporter,"id",40))) throw new IOException("导出器标识必须为 mkb_export");
        String exporterVersion=text(exporter,"version",128);
        var env=tree.path("environment");fields(env,"minecraftVersion","loader","loaderVersion","mods","resourcePacks","language");
        if(env.has("language")&&!text(env,"language",32).matches("[a-z0-9_]+"))throw new IOException("导出语言标识无效");
        String version=text(env,"minecraftVersion",64),loader=text(env,"loader",32);text(env,"loaderVersion",128);
        if(!Set.of("neoforge","forge","fabric","quilt","vanilla").contains(loader)) throw new IOException("未知加载器");
        var mods=array(env,"mods",4096);var ids=new HashSet<String>();
        for(var mod:mods) {
            fields(mod,"id","version","sha256");String id=text(mod,"id",255);
            if(!id.matches("[a-z0-9_.-]+") || !ids.add(id)) throw new IOException("模组 ID 无效或重复");
            text(mod,"version",128);if(mod.has("sha256")) hash(text(mod,"sha256",64));
        }
        var packs=array(env,"resourcePacks",256);ids.clear();
        for(var resource:packs) {fields(resource,"id","sha256");if(!ids.add(text(resource,"id",512)))throw new IOException("资源包 ID 重复");hash(text(resource,"sha256",64));}
        var rendering=tree.path("rendering");fields(rendering,"context","width","height","background");
        int width=rendering.path("width").asInt(-1),height=rendering.path("height").asInt(-1);
        if(!"GUI".equals(text(rendering,"context",16)) || !"transparent".equals(text(rendering,"background",32))
                || !rendering.path("width").isIntegralNumber() || !rendering.path("height").isIntegralNumber() || !rendering.path("width").canConvertToInt() || !rendering.path("height").canConvertToInt() || width<16 || width>512 || height!=width) throw new IOException("仅支持 16–512 像素的正方形透明 GUI 图标");
        var rows=array(tree,"icons",50000);if(rows.isEmpty())throw new IOException("导出清单没有物品记录");
        var icons=new ArrayList<Icon>();ids.clear();long total=0;
        for(var row:rows) {
            cancelled();fields(row,"itemId","variant","status","file","sha256","error","state");
            String item=text(row,"itemId",500);
            String variant=text(row,"variant",80);JsonNode state=row.get("state");
            if(!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || item.contains("..") || !ids.add(item+"|"+variant)) throw new IOException("物品注册名或状态无效或重复："+item);
            if(schema==1&&(!variant.equals("default")||state!=null))throw new IOException("协议 v1 仅支持 default 状态");
            if(!variant.equals(stateVariant(state)))throw new IOException("状态身份与内容哈希不符");
            String status=text(row,"status",32);
            if(Set.of("OK","TRANSPARENT").contains(status)) {
                String file=text(row,"file",1000),sha=text(row,"sha256",64);hash(sha);
                if(!file.startsWith("icons/") || !file.endsWith(".png") || row.has("error")) throw new IOException("成功图标必须引用 icons/ 内的 PNG");
                var icon=new Icon(item,status,file,sha,null,variant,state);
                total+=validate(root,icon,width);if(total>MAX_TOTAL)throw new IOException("导出图标总量超过 512 MiB");icons.add(icon);
            } else if(Set.of("FAILED","UNSUPPORTED").contains(status)) {
                if(row.has("file") || row.has("sha256"))throw new IOException("失败记录不能携带可用图标映射");
                icons.add(new Icon(item,status,null,null,text(row,"error",2000),variant,state));
            } else throw new IOException("未知图标状态："+status);
        }
        icons.sort(Comparator.comparing(Icon::itemId).thenComparing(Icon::variant));
        ObjectNode normalized=((ObjectNode)tree).deepCopy();normalized.remove("exportedAt");
        var sortedMods=new ArrayList<JsonNode>();mods.forEach(sortedMods::add);sortedMods.sort(Comparator.comparing(m -> m.path("id").asText()));
        var sortedArray=JSON.createArrayNode();sortedMods.forEach(sortedArray::add);((ObjectNode)normalized.path("environment")).set("mods",sortedArray);
        var sortedIcons=new ArrayList<JsonNode>();rows.forEach(sortedIcons::add);sortedIcons.sort(Comparator.comparing((JsonNode m) -> m.path("itemId").asText()).thenComparing(m->m.path("variant").asText()));
        var iconArray=JSON.createArrayNode();sortedIcons.forEach(iconArray::add);normalized.set("icons",iconArray);
        String canonical=canonical(normalized).toString(),environment=canonical(normalized.path("environment")).toString();
        Path real=root.toRealPath();String snapshotIdentity="";
        Path snapshot=ArchivePaths.resolve(real,"snapshot.json");
        if(Files.exists(snapshot,LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isRegularFile(snapshot,LinkOption.NOFOLLOW_LINKS)||Files.size(snapshot)>1024*1024)throw new IOException("知识快照声明类型或大小无效");
            snapshotIdentity="\n"+ArtifactFingerprint.sha256(snapshot);
        }
        var pack=new Pack(real,yagen.waitmydawn.kb.model.BuildRequest.fingerprint(canonical+snapshotIdentity),yagen.waitmydawn.kb.model.BuildRequest.fingerprint(environment),origin,exporterVersion,version,loader,normalized.path("environment"),rendering,canonical,List.copyOf(icons));
        ExportSnapshotPreflight.inspect(pack,null);
        return pack;
    }

    static long validate(Path root,Icon icon,int width) throws IOException {
        Path file=ArchivePaths.resolve(root,icon.file());rejectSensitive(file);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>MAX_ICON)throw new IOException("图标不存在或超过 8 MiB："+icon.itemId());
        if(!ArtifactFingerprint.sha256(file).equals(icon.sha256()))throw new IOException("图标哈希不符："+icon.itemId());
        try(var input=ImageIO.createImageInputStream(file.toFile())) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("图标无法解码");var reader=readers.next();
            try {
                reader.setInput(input);
                if(!reader.getFormatName().equalsIgnoreCase("png") || reader.getWidth(0)!=width || reader.getHeight(0)!=width)throw new IOException("PNG 尺寸与清单不符："+icon.itemId());
                var image=reader.read(0);if(image==null || !image.getColorModel().hasAlpha())throw new IOException("图标缺少 Alpha 通道："+icon.itemId());
                if(icon.status().equals("TRANSPARENT"))for(int y=0;y<width;y++)for(int x=0;x<width;x++)
                    if((image.getRGB(x,y)>>>24)!=0)throw new IOException("声明完全透明的图标含可见像素："+icon.itemId());
            } finally {reader.dispose();}
        }
        return Files.size(file);
    }
    static JsonNode canonical(JsonNode value) {
        if(value.isObject()) {var result=JSON.createObjectNode();var names=new ArrayList<String>();value.fieldNames().forEachRemaining(names::add);Collections.sort(names);for(String name:names)result.set(name,canonical(value.get(name)));return result;}
        if(value.isArray()) {var result=JSON.createArrayNode();value.forEach(v -> result.add(canonical(v)));return result;}return value;
    }
    public static String stateVariant(JsonNode state)throws IOException {
        if(state==null)return "default";
        if(!state.isObject()||state.size()!=1||!(state.has("components")&&state.path("components").isObject()&&!state.path("components").isEmpty()
                ||state.has("nbt")&&state.path("nbt").isTextual()&&!state.path("nbt").asText().isBlank()))throw new IOException("状态必须包含非空 components 对象或 nbt 字符串");
        String value=canonical(state).toString().replace("\u2028","\\u2028").replace("\u2029","\\u2029");
        if(value.length()>65536)throw new IOException("状态超过 64 KiB");
        return "state:"+yagen.waitmydawn.kb.model.BuildRequest.fingerprint(value);
    }
    private static void fields(JsonNode node,String... allowed) throws IOException {
        if(node==null || !node.isObject())throw new IOException("清单字段必须为对象");var names=Set.of(allowed);
        var fields=node.fieldNames();while(fields.hasNext())if(!names.contains(fields.next()))throw new IOException("清单含未知字段");
    }
    private static JsonNode array(JsonNode node,String field,int max) throws IOException {var result=node.path(field);if(!result.isArray() || result.size()>max)throw new IOException(field+" 必须为有界数组");return result;}
    private static String text(JsonNode node,String field,int max) throws IOException {var value=node.path(field);if(!value.isTextual() || value.asText().isBlank() || value.asText().length()>max)throw new IOException("清单字段缺失或无效："+field);return value.asText();}
    private static void hash(String hash)throws IOException {if(!hash.matches("[0-9a-f]{64}"))throw new IOException("必须使用小写 SHA-256");}
    static void rejectSensitive(Path path)throws IOException {for(Path p:path.toAbsolutePath().normalize())if(Set.of("secrets","credentials",".ssh",".aws",".maa_kb").contains(p.toString().toLowerCase(Locale.ROOT)))throw new IOException("不能读取敏感目录");}
    private static void cancelled(){BuildCancellation.check();}
}
