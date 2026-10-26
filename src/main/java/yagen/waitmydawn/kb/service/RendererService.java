package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.renderer.RecipeCardRenderer;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;

/** 配方语义、显式环境图标和不可变回答图片之间的编排；缓存独立于文本向量代次。 */
public class RendererService {
    private static final Logger LOG=LoggerFactory.getLogger(RendererService.class);
    private static final ObjectMapper JSON=new ObjectMapper();
    private final AppConfig config;
    private final DatabaseBuilder db;
    public record IconEvidence(String itemId,String assetId,String source,String detail) {}
    public record SlotEvidence(int index,String role,int count,List<RecipePlan.Alternative> alternatives,
                               List<String> candidates,IconEvidence preview,List<String> warnings) {}
    public record RenderedRecipe(String key,String path,String fingerprint,String recipeId,String recipeJson,
                                 String sourceMod,String sourceType,String profileId,RecipePlan plan,
                                 List<SlotEvidence> slots,IconEvidence output,List<String> metadata,List<String> notes,String pngHash) {
        public String summary() {
            String text=String.join("\n",metadata)+"\n"+String.join("\n",notes)+"\n"+slots.stream().map(s -> s.role()+" ×"+s.count()+" "+s.alternatives()+"；预览 "+s.preview().itemId()+"；"+s.preview().source()+"；"+s.preview().detail()).collect(java.util.stream.Collectors.joining("\n"));
            return text.substring(0,Math.min(text.length(),16000));
        }
    }
    public record RenderBatch(Map<String,String> images,List<RenderedRecipe> cards,List<String> diagnostics) {
        public RenderBatch {images=Collections.unmodifiableMap(new LinkedHashMap<>(images));cards=List.copyOf(cards);diagnostics=List.copyOf(diagnostics);}
    }
    private record RecipeMeta(String id,String loader,String version,String minecraftVersion) {}
    public RendererService(AppConfig config,DatabaseBuilder db) {this.config=config;this.db=db;}
    public Map<String,String> renderRecipes(List<QaPipeline.RecipeMatch> matches) {return renderDetailed(matches).images();}
    public RenderBatch renderDetailed(List<QaPipeline.RecipeMatch> matches) {
        var images=new LinkedHashMap<String,String>();var cards=new ArrayList<RenderedRecipe>();var diagnostics=new ArrayList<String>();
        if(matches==null||matches.isEmpty())return new RenderBatch(images,cards,diagnostics);
        var icons=new ItemIconService(db,config.getDataDir()).snapshot();
        for(var match:matches) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Recipe rendering cancelled");
            try {
                if("SEED".equals(match.sourceType()))throw new IllegalArgumentException("内置示例配方未核对版本，请先导入真实 JAR 配方");
                var plan=RecipePlan.parse(match.recipeJson());
                if(!plan.output().dynamic()&&match.outputItem()!=null&&!match.outputItem().equals(plan.output().itemId()))throw new IllegalArgumentException("配方产物与检索结果不一致");
                var meta=metadata(match);var inputs=new ArrayList<RecipeCardRenderer.Input>();var evidence=new ArrayList<SlotEvidence>();
                var notes=new LinkedHashSet<String>(plan.notes());var metadata=new ArrayList<String>();
                String sourceType=Objects.toString(match.sourceType(),"UNKNOWN");
                metadata.add("来源："+(sourceType.equals("JAR_PARSE")?"JAR 默认配方，未确认运行时修改":sourceType));
                metadata.add("配方："+meta.id());
                metadata.add("来源模组："+Objects.toString(match.sourceMod(),"未知")+" "+Objects.toString(meta.version(),""));
                var profile=icons.profile();
                if(profile!=null && (meta.minecraftVersion()!=null && meta.minecraftVersion().matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?") && !meta.minecraftVersion().equals(profile.minecraftVersion())
                        || meta.loader()!=null && !meta.loader().isBlank() && !meta.loader().equals("vanilla") && !meta.loader().equals(profile.loader())))throw new IllegalArgumentException("配方版本与所选图标环境不符");
                metadata.add("配方版本："+Objects.toString(meta.minecraftVersion(),"未知")+" · "+Objects.toString(meta.loader(),"未知加载器"));
                metadata.add(profile==null?"图标环境：仅静态回退 / 缺失占位":"图标环境："+profile.loader()+" "+profile.minecraftVersion()+" · "+profile.origin());
                if(profile!=null)metadata.add("环境确认："+profile.compatibility()+" · "+profile.id().substring(0,8));
                else notes.add("未选择实机图标环境；不会自动挑选或跨包借用图标。");
                if(profile!=null&&!profile.compatibility().equals("DECLARED_MATCH"))notes.add("环境未完整验证或存在冲突；已知冲突的实机包不会用于绘图。");
                if(profile!=null&&profile.origin().equals("FIXTURE"))notes.add("当前为模拟图标环境，不能作为实机效果验收。");
                for(var slot:plan.slots()) {
                    var selected=icons.ingredient(slot.ingredient());var icon=selected.icon();
                    var warnings=new ArrayList<>(selected.warnings());
                    if(!icon.source().equals("NATIVE"))warnings.add(Objects.toString(icon.itemId(),slot.role())+"："+icon.detail());
                    notes.addAll(warnings);
                    String constraints=slot.ingredient().unsupported()!=null?slot.ingredient().unsupported():slot.ingredient().alternatives().stream().map(RecipePlan.Alternative::label).collect(java.util.stream.Collectors.joining(" / "));
                    String preview=icon.itemId()==null?"未确定":icon.name()+" ("+icon.itemId()+")";
                    String description=constraints+"；预览："+preview+"；"+origin(icon.source());
                    if(selected.candidates().size()>1)description+="；已知候选 "+selected.candidates().size()+" 种";
                    inputs.add(new RecipeCardRenderer.Input(slot,visual(icon),description));
                    evidence.add(new SlotEvidence(slot.index(),slot.role(),slot.ingredient().count(),slot.ingredient().alternatives(),selected.candidates(),proof(icon),warnings));
                }
                var output=icons.item(plan.output().itemId(),plan.output().stateful()||plan.output().dynamic());
                if(!output.source().equals("NATIVE"))notes.add("产物："+output.detail());
                var counts=new TreeMap<String,Integer>();inputs.forEach(i->counts.merge(origin(i.visual().source()),1,Integer::sum));counts.merge(origin(output.source()),1,Integer::sum);
                metadata.add("图标槽位："+counts);
                String fingerprint=BuildRequest.fingerprint(RecipeCardRenderer.VERSION+"\n"+icons.revision()+"\n"+match.recipeJson()+"\n"+metadata+"\n"+JSON.writeValueAsString(evidence)+"\n"+proof(output));
                String outputKey=plan.output().dynamic()?"minecraft:smithing_trim":plan.output().itemId();
                String key=outputKey+"#"+fingerprint.substring(0,24);
                String family=switch(plan.kind()){case SHAPED,SHAPELESS->"crafting";case SMITHING_TRANSFORM,SMITHING_TRIM->"smithing";case STONECUTTING->"stonecutting";default->"furnace";};
                Path directory=config.getGeneratedDir().resolve(family);Files.createDirectories(directory);
                String safe=outputKey.replaceAll("[^a-zA-Z0-9_-]","_");if(safe.length()>80)safe=safe.substring(0,80);
                Path file=directory.resolve(safe+"_"+fingerprint.substring(0,24)+".png");Path sidecar=directory.resolve(file.getFileName()+".json");
                String pngHash=cachedHash(file,sidecar,fingerprint);
                if(pngHash==null) {
                    var image=RecipeCardRenderer.render(new RecipeCardRenderer.Card(plan,inputs,visual(output),metadata,List.copyOf(notes)));
                    Path temp=Files.createTempFile(directory,"recipe-",".png");
                    try {ImageIO.write(image,"PNG",temp.toFile());pngHash=ArtifactFingerprint.sha256(temp);move(temp,file);}finally{Files.deleteIfExists(temp);}
                }
                var rendered=new RenderedRecipe(key,file.toAbsolutePath().toString(),fingerprint,meta.id(),match.recipeJson(),match.sourceMod(),sourceType,profile==null?null:profile.id(),plan,evidence,proof(output),metadata,List.copyOf(notes),pngHash);
                byte[] report=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(rendered);if(report.length>16*1024*1024)throw new IllegalArgumentException("配方说明超过归档预算");
                Path temp=Files.createTempFile(directory,"recipe-",".json");try{Files.write(temp,report);move(temp,sidecar);}finally{Files.deleteIfExists(temp);}
                images.put(key,rendered.path());cards.add(rendered);
            } catch(Exception e) {
                String message=Objects.toString(match.outputItem(),"未知产物")+"："+e.getMessage();diagnostics.add(message);LOG.warn("Recipe rendering skipped: {}",message);
            }
        }
        return new RenderBatch(images,cards,diagnostics);
    }
    private RecipeMeta metadata(QaPipeline.RecipeMatch match) {
        String id=Objects.toString(match.sourceMod(),"unknown")+":recipe/"+BuildRequest.fingerprint(match.recipeJson()).substring(0,12),loader=null,version=null,mc=null;
        var mod=match.sourceMod()==null?null:db.findModEntry(match.sourceMod());if(mod!=null){loader=mod.getLoader();version=mod.getVersion();mc=mod.getMcVersion();}
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT recipe_id,recipe_data,loader,mod_version FROM rag_recipe WHERE source_mod=? AND "+(match.outputItem()==null?"output_item IS NULL":"output_item=?")+" ORDER BY id")) {
            p.setString(1,match.sourceMod());if(match.outputItem()!=null)p.setString(2,match.outputItem());p.setMaxRows(1000);
            try(var r=p.executeQuery()){while(r.next())if(Objects.equals(r.getString(2),match.recipeJson())){id=Objects.toString(r.getString(1),id);loader=r.getString(3);version=r.getString(4);break;}}
        }catch(Exception e){LOG.debug("Recipe metadata lookup unavailable",e);}
        return new RecipeMeta(id,loader,version,mc);
    }
    private static RecipeCardRenderer.Visual visual(ItemIconService.Icon icon){return new RecipeCardRenderer.Visual(icon.itemId(),icon.name(),icon.image(),icon.source(),icon.detail());}
    private static IconEvidence proof(ItemIconService.Icon icon){return new IconEvidence(icon.itemId(),icon.assetId(),icon.source(),icon.detail());}
    private static String origin(String value){return switch(value){case "NATIVE"->"实机";case "JAR_FLAT"->"JAR 平面";default->"缺失/动态";};}
    private static String cachedHash(Path file,Path sidecar,String fingerprint) {
        try {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(sidecar,LinkOption.NOFOLLOW_LINKS)||Files.size(sidecar)>16*1024*1024)return null;
            var metadata=JSON.readTree(sidecar.toFile());String expected=metadata.path("pngHash").asText();
            return metadata.path("fingerprint").asText().equals(fingerprint)&&expected.equals(ArtifactFingerprint.sha256(file))?expected:null;
        }catch(Exception ignored){return null;}
    }
    private static void move(Path source,Path target)throws java.io.IOException {
        try{Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);}
    }
    public String imageMarkdown(String outputItem,String path){return "![recipe:"+outputItem.split("#",2)[0]+"]("+path.replace('\\','/')+")";}
    /** 图标和资源在每批读取快照，接口保留以兼容既有生命周期调用。 */
    public void clearCache() {}
}
