package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.model.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 每批渲染固定一个环境快照；不按短名查图，也不将标签身份当作物品身份。 */
public final class ItemIconService {
    public record Icon(String itemId, String name, String assetId, String source, String detail, BufferedImage image) {
        public String identity() { return Objects.toString(itemId, "") + "|" + Objects.toString(assetId, "") + "|" + source + "|" + detail; }
    }
    public record IngredientIcon(RecipePlan.Ingredient ingredient, Icon icon, List<String> candidates, List<String> warnings) {}
    private record Asset(String id, String path, String status) {}
    private final DatabaseBuilder db;
    private final Path dataDir;
    public ItemIconService(DatabaseBuilder db, Path dataDir) { this.db = db; this.dataDir = dataDir; }
    public Snapshot snapshot() {
        var store = new ItemIconStore(db); String active = store.activeProfileId();
        var profile = store.profiles().stream().filter(p -> p.id().equals(active)).findFirst().orElse(null);
        try (var c = db.getConnection()) {
            c.setAutoCommit(false); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            var nativeIcons = new TreeMap<String, Asset>(); var flatIcons = new TreeMap<String, Asset>();
            if (profile != null && !profile.compatibility().equals("CONFLICT")) {
                try (var p = c.prepareStatement("SELECT i.item_id,i.image_id,a.relative_path,i.status,i.variant FROM rag_item_icon i LEFT JOIN rag_image_asset a ON a.id=i.image_id WHERE i.profile_id=? ORDER BY i.item_id,i.variant")) {
                    p.setString(1, active); try (var r = p.executeQuery()) { while (r.next()) nativeIcons.put(r.getString(1)+"|"+r.getString(5), new Asset(r.getString(2),r.getString(3),r.getString(4))); }
                }
            }
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT i.id,i.image_id,a.relative_path,i.status FROM rag_item_static_icon i LEFT JOIN rag_image_asset a ON a.id=i.image_id ORDER BY i.id")) {
                while (r.next()) flatIcons.put(r.getString(1), new Asset(r.getString(2),r.getString(3),r.getString(4)));
            }
            var names = new TreeMap<String, Map<String, Set<String>>>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT item_id,language,display_name FROM rag_item_name ORDER BY id")) {
                while (r.next()) names.computeIfAbsent(r.getString(1), k -> new TreeMap<>()).computeIfAbsent(r.getString(2), k -> new TreeSet<>()).add(r.getString(3));
            }
            var tags = RecipeResourceStore.tags(c);
            var stamp = new ArrayList<Object>(); stamp.add(profile); stamp.add(nativeIcons); stamp.add(flatIcons); stamp.add(names); stamp.add(tags);
            stamp.add(db.buildGeneration());
            String revision = BuildRequest.fingerprint(new ObjectMapper().writeValueAsString(stamp));
            c.commit(); return new Snapshot(profile,revision,nativeIcons,flatIcons,names,tags,dataDir);
        } catch (Exception e) { throw new IllegalStateException("配方图标快照读取失败",e); }
    }
    public static final class Snapshot {
        private final ItemIconStore.Profile profile;
        private final String revision;
        private final Map<String,Asset> nativeIcons, flatIcons;
        private final Map<String,Map<String,Set<String>>> names;
        private final Map<String,List<RecipeResourceStore.TagFile>> tags;
        private final ImageAssetStore images;
        private final Map<String,Icon> cache = new HashMap<>();
        Snapshot(ItemIconStore.Profile profile,String revision,Map<String,Asset> nativeIcons,Map<String,Asset> flatIcons,
                 Map<String,Map<String,Set<String>>> names,Map<String,List<RecipeResourceStore.TagFile>> tags,Path dataDir) {
            this.profile=profile; this.revision=revision; this.nativeIcons=nativeIcons; this.flatIcons=flatIcons;
            this.names=names; this.tags=tags; this.images=new ImageAssetStore(dataDir);
        }
        public ItemIconStore.Profile profile() { return profile; }
        public String revision() { return revision; }
        public String name(String id) {
            var languages=names.get(id); if(languages==null) return id;
            for(String language:List.of("zh_cn","en_us")) {
                var values=languages.get(language); if(values!=null && values.size()==1) return values.iterator().next();
            }
            return id;
        }
        public Icon item(String id,boolean stateful) {
            return item(id,stateful,null);
        }
        public Icon item(String id,boolean stateful,com.fasterxml.jackson.databind.JsonNode state) {
            if(stateful) {
                if(id==null||state==null)return missing(id,"指定组件/NBT 状态尚未导出");
                try {
                    String variant=NativeIconPack.stateVariant(state);var asset=nativeIcons.get(id+"|"+variant);
                    if(asset!=null&&"TRANSPARENT".equals(asset.status()))return transparent(id,asset);
                    var image=load(asset,"OK");
                    return image==null?missing(id,"指定组件/NBT 状态尚未导出："+variant):new Icon(id,name(id),asset.id(),"NATIVE","实机精确状态 "+variant,image);
                }catch(java.io.IOException invalid){return missing(id,"指定组件/NBT 状态格式无效");}
            }
            if(id==null)return missing(null,"动态产物，不能推断精确图标");
            return cache.computeIfAbsent(id, key -> {
                var nativeIcon=nativeIcons.get(key+"|default");
                if(nativeIcon!=null&&"TRANSPARENT".equals(nativeIcon.status()))return transparent(key,nativeIcon);
                var nativeImage=load(nativeIcon,"OK");
                if(nativeImage!=null)return new Icon(key,name(key),nativeIcon.id(),"NATIVE","实机默认状态",nativeImage);
                var flat=flatIcons.get(key); var flatImage=load(flat,"FLAT_MODEL");
                if(flatImage!=null)return new Icon(key,name(key),flat.id(),"JAR_FLAT","JAR 单层平面候选，未验证运行时着色",flatImage);
                String reason=profile!=null && profile.compatibility().equals("CONFLICT") ? "图标环境冲突；未使用该实机包" : nativeIcon!=null ? "实机图标失败、丢失或校验不符" : "没有匹配的默认图标或可用平面模型";
                return missing(key,reason);
            });
        }
        public IngredientIcon ingredient(RecipePlan.Ingredient ingredient) {
            var candidates=new LinkedHashSet<String>(); var warnings=new ArrayList<String>();
            if(ingredient.unsupported()!=null)return new IngredientIcon(ingredient,missing(null,ingredient.unsupported()),List.of(),List.of(ingredient.unsupported()));
            for(var alternative:ingredient.alternatives()) {
                if(!alternative.tag())candidates.add(alternative.id());
                else {
                    var result=RecipeResourceStore.resolveTag(alternative.id(),tags);
                    if(result.resolved())candidates.addAll(result.items()); else warnings.add(result.problem());
                }
            }
            Icon selected=null;
            for(String id:candidates) { var icon=item(id,ingredient.stateful(),ingredient.state()); if(selected==null || rank(icon)<rank(selected))selected=icon; }
            if(selected==null)selected=missing(ingredient.alternatives().isEmpty()?null:ingredient.alternatives().getFirst().label(),"材料标签或候选未解析");
            if(ingredient.alternatives().stream().anyMatch(RecipePlan.Alternative::tag))warnings.add("标签候选来自 JAR 声明，未确认整合包运行时覆盖。");
            return new IngredientIcon(ingredient,selected,List.copyOf(candidates),List.copyOf(warnings));
        }
        private static int rank(Icon icon) { return Set.of("NATIVE","NATIVE_TRANSPARENT").contains(icon.source())?0:icon.source().equals("JAR_FLAT")?1:2; }
        private Icon transparent(String id,Asset asset) {
            var image=load(asset,"TRANSPARENT");
            return image==null?missing(id,"完全透明图标缓存丢失或校验不符"):
                    new Icon(id,name(id),asset.id(),"NATIVE_TRANSPARENT","实机完全透明（本次 GUI 渲染无可见像素）",image);
        }
        private Icon missing(String id,String reason) { return new Icon(id,id==null?"未确定物品":name(id),null,"MISSING",reason,null); }
        private BufferedImage load(Asset asset,String status) {
            if(asset==null || !status.equals(asset.status()) || asset.id()==null || asset.path()==null)return null;
            try { if(!images.available(asset.id(),asset.path()))return null; return ImageIO.read(images.resolve(asset.id(),asset.path()).toFile()); }
            catch(Exception ignored) {return null;}
        }
    }
}
