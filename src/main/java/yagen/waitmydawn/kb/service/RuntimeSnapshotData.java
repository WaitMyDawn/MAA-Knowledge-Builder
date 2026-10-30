package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import yagen.waitmydawn.kb.model.RecipePlan;

/** 分类载荷的语义边界；失败记录可保留，但不能参与名称、材料或产物推断。 */
public final class RuntimeSnapshotData {
    private static final ObjectMapper JSON=new ObjectMapper();
    public record Data(JsonNode descriptor,Map<String,JsonNode> categories) {}
    private RuntimeSnapshotData() {}
    public static Data read(NativeIconPack.Pack pack)throws IOException {
        try(var timingScope=BuildTrace.step("snapshot.read",pack.root().toString())) {

        Path descriptor=ArchivePaths.resolve(pack.root(),"snapshot.json");
        if(!Files.exists(descriptor)) {
            if(!yagen.waitmydawn.kb.model.BuildRequest.fingerprint(pack.manifestJson()).equals(pack.manifestHash()))throw new IOException("预检查后知识声明丢失");
            return new Data(JSON.createObjectNode(),Map.of());
        }
        byte[] descriptorBytes=bytes(descriptor,1024*1024);
        if(!yagen.waitmydawn.kb.model.BuildRequest.fingerprint(pack.manifestJson()+"\n"+BuildHash.sha256(descriptorBytes)).equals(pack.manifestHash()))throw new IOException("预检查后知识声明发生变化");
        JsonNode root=JSON.readTree(descriptorBytes);var payloads=new TreeMap<String,JsonNode>();
        for(String category:List.of("mod_metadata","registries","language","recipes","tags","loot_tables","loot_modifiers","entity_icons")) {
            var declaration=root.path("categories").path(category);
            if(!Set.of("COMPLETE","COMPLETE_WITH_ERRORS").contains(declaration.path("status").asText()))continue;
            Path file=ArchivePaths.resolve(pack.root(),"data/"+category+".json");byte[] bytes=bytes(file,64*1024*1024);
            if(!BuildHash.sha256(bytes).equals(declaration.path("sha256").asText()))throw new IOException("快照载荷发生变化："+category);
            JsonNode payload=JSON.readTree(bytes);validate(category,payload,declaration,pack.root());
            if(category.equals("entity_icons")&&!payload.has("imageStorage")){var images=EntityImageReferences.index(payload.path("records"));for(var row:payload.path("records"))RuntimeEntityMedia.validateExternal(pack.root(),EntityImageReferences.resolve(row,images));}
            payloads.put(category,payload);
        }
        return new Data(root,Collections.unmodifiableMap(payloads));
        }
    }
    static void validate(String category,JsonNode payload,JsonNode declaration)throws IOException {validate(category,payload,declaration,null);}
    static void validate(String category,JsonNode payload,JsonNode declaration,Path root)throws IOException {
        boolean external=payload!=null&&payload.has("imageStorage");
        if(external&&(!category.equals("entity_icons")||!payload.path("imageStorage").asText().equals("EXTERNAL_PNG")))throw new IOException("未知实体图片存储协议");
        if(payload==null||!payload.isObject()||!"mkb-data".equals(payload.path("format").asText())||!payload.path("schemaVersion").isIntegralNumber()||payload.path("schemaVersion").asInt()!=1
                ||!category.equals(payload.path("category").asText())||!payload.path("authoritative").isBoolean()||!payload.path("authoritative").asBoolean()
                ||!payload.path("records").isArray()||payload.path("records").size()>250_000||payload.path("records").size()!=declaration.path("total").asInt())throw new IOException("知识载荷结构无效："+category);
        if(category.equals("entity_icons")){var paths=EntityAppearanceState.flatPaths(payload.path("records"));for(var row:payload.path("records"))if(!row.has("error")&&row.has("file")){String file=row.path("file").asText();if(!file.equals(EntityAppearanceState.path(row))&&!file.equals(EntityAppearanceState.readablePath(row))&&!file.equals(paths.get(row.path("id").asText()+"|"+EntityAppearanceState.key(row))))throw new IOException("实体扁平文件名与批次编号不一致");}}
        var imageRows=category.equals("entity_icons")?EntityImageReferences.index(payload.path("records")):Map.<String,JsonNode>of();
        long imageBytes=0;var imageFiles=new HashSet<String>();
        var identities=new HashSet<String>();var entityIds=new HashSet<String>();int failed=0,transparent=0;
        for(var row:payload.path("records")) {
            if(!row.isObject())throw new IOException("知识记录不是对象");String id=text(row,"id",500);
            String identity=(category.equals("registries")||category.equals("tags")?text(row,"registry",500)+"|":"")+id;
            if(category.equals("entity_icons")){identity+="|"+EntityAppearanceState.key(row);entityIds.add(id);}
            if(!identities.add(identity))throw new IOException("重复知识记录："+identity);
            if(row.has("error")){text(row,"error",2000);failed++;continue;}
            switch(category) {
                case "mod_metadata" -> {if(!id.matches("[a-z0-9_.-]+"))throw new IOException("模组 ID 无效");text(row,"version",128);text(row,"displayName",1000);}
                case "registries" -> {
                    resource(id);if(!Set.of("item","block","entity_type").contains(row.path("registry").asText()))throw new IOException("未知对象注册表");
                    text(row,"descriptionId",1000);text(row,"displayName",2000);
                    if(row.has("tooltip")){if(!row.path("tooltip").isArray()||row.path("tooltip").size()>1024)throw new IOException("Tooltip 数量超限");for(var line:row.path("tooltip"))if(!line.isTextual()||line.asText().length()>16384)throw new IOException("Tooltip 文本无效");}
                }
                case "language" -> {if(!Set.of("en_us","zh_cn","current").contains(id)||!row.path("translations").isObject()||row.path("translations").size()>250_000)throw new IOException("语言映射无效");var fields=row.path("translations").fields();while(fields.hasNext()){var field=fields.next();if(field.getKey().length()>1000||!field.getValue().isTextual()||field.getValue().asText().length()>65536)throw new IOException("翻译条目无效");}}
                case "tags" -> {resource(id);resource(row.path("registry").asText());if(!row.path("members").isArray()||row.path("members").size()>100_000)throw new IOException("标签成员超限");var members=new HashSet<String>();for(var member:row.path("members")){resource(member.asText());if(!members.add(member.asText()))throw new IOException("标签成员重复");}}
                case "entity_icons" -> {if(row.has("imageRef")&&payload.path("imageReferencesVersion").asInt()!=1)throw new IOException("未声明实体图片引用协议");var resolved=EntityImageReferences.resolve(row,imageRows);
                    if(external){if(row.has("pngBase64")||row.has("assetPath"))throw new IOException("独立实体图片记录不能内嵌图片或本地资产路径");byte[] png=RuntimeEntityMedia.externalBytes(root,resolved);if(imageFiles.add(resolved.path("file").asText())){imageBytes+=png.length;if(imageBytes>64L*1024*1024)throw new IOException("实体 PNG 总量超限");}RuntimeEntityMedia.validate(resolved,png);}
                    else RuntimeEntityMedia.validate(resolved);if(row.path("status").asText().equals("TRANSPARENT"))transparent++;}
                case "loot_tables","loot_modifiers" -> {
                    resource(id);if(!row.path("runtimeLoaded").isBoolean()||!row.path("runtimeLoaded").asBoolean()||!row.path("definition").isObject())throw new IOException("运行时掉落定义无效");
                    if(category.equals("loot_modifiers")&&(!row.path("order").isIntegralNumber()||row.path("order").asInt(-1)<0||!"CAPTURE_ORDER".equals(row.path("identity").asText())))throw new IOException("掉落修饰器顺序无效");
                }
                case "recipes" -> {
                    resource(id);resource(text(row,"serializer",500));resource(text(row,"recipeType",500));
                    if(!row.path("runtimeLoaded").isBoolean()||!row.path("runtimeLoaded").asBoolean()||!Set.of("STATIC","DYNAMIC","UNSUPPORTED").contains(row.path("normalizationStatus").asText()))throw new IOException("运行时配方状态无效");
                    if(!Set.of("CODEC_JSON","RUNTIME_ADAPTER","NETWORK_ONLY").contains(row.path("serialization").asText()))throw new IOException("配方序列化类型无效");
                    if(row.has("definition")&&!row.path("definition").isObject())throw new IOException("配方定义无效");
                    if(row.has("renderJson")&&(!row.path("renderJson").isObject()||!row.path("renderJson").equals(row.path("definition"))||!row.path("serializer").asText().equals(row.path("renderJson").path("type").asText())))throw new IOException("配方绘图定义不一致");
                    if(row.has("networkBase64")){String encoded=text(row,"networkBase64",3*1024*1024);try{if(Base64.getDecoder().decode(encoded).length>2*1024*1024)throw new IOException("配方网络数据超限");}catch(IllegalArgumentException invalid){throw new IOException("配方网络数据无效",invalid);}}
                }
                default -> throw new IOException("未知知识类别");
            }
        }
        if(category.equals("entity_icons")&&payload.has("exclusions")) {
            var exclusions=payload.path("exclusions");if(!exclusions.isArray()||exclusions.size()>250000)throw new IOException("实体跳过记录无效");var excluded=new HashSet<String>();
            for(var row:exclusions){String id=text(row,"id",500);resource(id);if(entityIds.contains(id)||!excluded.add(id)||!Set.of("EXCLUDED_NON_LIVING","REQUIRES_CREATION_CONTEXT","DEBUG_ENTITY_REQUIRES_SPELL","NO_ENTITY_GEOMETRY_EFFECT_OR_LOGIC").contains(row.path("reason").asText()))throw new IOException("实体跳过身份或原因无效");}
        }
        boolean precise=category.equals("entity_icons")&&(payload.has("exclusions")||java.util.stream.StreamSupport.stream(payload.path("records").spliterator(),false).anyMatch(row->row.has("state")));
        if(precise&&transparent!=declaration.path("transparent").asInt())throw new IOException("实体透明计数不一致");
        if(failed!=declaration.path("failed").asInt()||payload.path("records").size()-failed-(category.equals("entity_icons")?declaration.path("transparent").asInt():0)!=declaration.path("succeeded").asInt()||category.equals("entity_icons")&&declaration.path("transparent").asInt()!=0&&transparent!=declaration.path("transparent").asInt())throw new IOException("知识记录计数不一致："+category);
    }
    static String text(JsonNode row,String field,int limit)throws IOException {var node=row.path(field);if(!node.isTextual()||node.asText().isBlank()||node.asText().length()>limit)throw new IOException("知识字段无效："+field);return node.asText();}
    private static void resource(String id)throws IOException {try{RecipePlan.resourceId(id);}catch(Exception invalid){throw new IOException("资源 ID 无效："+id,invalid);}}
    private static byte[] bytes(Path file,int limit)throws IOException {if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>limit)throw new IOException("载荷文件缺失或超限");try(var input=Files.newInputStream(file)){byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("载荷超限");return bytes;}}
    private static final class BuildHash {static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}}
}
