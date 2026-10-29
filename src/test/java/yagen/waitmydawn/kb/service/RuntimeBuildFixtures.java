package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;

/** 独立于用户目录的最小协议夹具；业务测试显式补入快照，历史图标测试直接使用归档导入 API。 */
public final class RuntimeBuildFixtures {
    static final ObjectMapper JSON=new ObjectMapper();
    private RuntimeBuildFixtures() {}
    public static BuildRequest withSnapshot(BuildRequest request,Path data) {
        try {
            Path root=data.resolve("build-fixture/MKB-Export").toAbsolutePath();
            if(!Files.exists(root.resolve("snapshot.json")))create(root,List.of(),"neoforge","1.21.1");
            var sources=new ArrayList<>(request.sources());sources.add(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString()));
            return new BuildRequest(sources,request.mode());
        }catch(Exception failure){throw new IllegalStateException("测试快照创建失败",failure);}
    }
    static Path create(Path root,List<ModEntry> mods,String loader,String version)throws Exception {
        Files.createDirectories(root.resolve("icons/minecraft"));Files.deleteIfExists(root.resolve("snapshot.json"));
        var png=new java.awt.image.BufferedImage(32,32,2);png.setRGB(15,15,0xffaaaaaa);Path file=root.resolve("icons/minecraft/stone.png");javax.imageio.ImageIO.write(png,"png",file.toFile());
        var manifest=JSON.createObjectNode().put("format","mkb-export").put("schemaVersion",1).put("origin","GAME_GUI");manifest.putObject("exporter").put("id","mkb_export").put("version","test-fixture");
        var env=manifest.putObject("environment").put("minecraftVersion",version).put("loader",loader).put("loaderVersion","test");env.putArray("resourcePacks");var installed=env.putArray("mods");installed.addObject().put("id","minecraft").put("version",version);
        for(var mod:mods)if(!mod.getModId().equals("minecraft")){var row=installed.addObject().put("id",mod.getModId()).put("version",mod.getVersion());if(mod.getJarPath()!=null)row.put("sha256",ArtifactFingerprint.sha256(Path.of(mod.getJarPath())));}
        manifest.putObject("rendering").put("context","GUI").put("width",32).put("height",32).put("background","transparent");manifest.putArray("icons").addObject().put("itemId","minecraft:stone").put("variant","default").put("status","OK").put("file","icons/minecraft/stone.png").put("sha256",ArtifactFingerprint.sha256(file));
        JSON.writeValue(root.resolve("manifest.json").toFile(),manifest);
        var snapshot=JSON.createObjectNode().put("format","mkb-snapshot").put("schemaVersion",1).put("captureMode","SINGLEPLAYER").put("coverage","ALL").put("clientFingerprint",NativeIconPack.read(root).environmentHash()).put("serverFingerprint","1".repeat(64)).put("serverSession","11111111-1111-1111-1111-111111111111").put("serverGeneration",1);
        var categories=snapshot.putObject("categories");
        for(String category:ExportSnapshotPreflight.REQUIRED){var declaration=categories.putObject(category).put("total",0).put("succeeded",0).put("transparent",0).put("unsupported",0).put("failed",0);
            if(category.startsWith("loot_")){declaration.put("status","NOT_IMPLEMENTED");continue;}
            if(category.equals("item_icons")){declaration.put("status","COMPLETE").put("total",1).put("succeeded",1).put("file","manifest.json").put("sha256",ArtifactFingerprint.sha256(root.resolve("manifest.json")));continue;}
            var payload=JSON.createObjectNode().put("format","mkb-data").put("schemaVersion",1).put("category",category).put("authoritative",true);var rows=payload.putArray("records");
            if(category.equals("mod_metadata"))for(var mod:installed)rows.addObject().put("id",mod.path("id").asText()).put("version",mod.path("version").asText()).put("displayName",mod.path("id").asText());
            if(category.equals("registries"))for(String id:List.of("minecraft:air","minecraft:stone"))rows.addObject().put("registry","item").put("id",id).put("descriptionId","item."+id.replace(':','.')).put("displayName",id);
            if(category.equals("registries"))rows.addObject().put("registry","entity_type").put("id","minecraft:pig").put("descriptionId","entity.minecraft.pig").put("displayName","minecraft:pig");
            if(category.equals("language"))for(String lang:List.of("current","zh_cn","en_us"))rows.addObject().put("id",lang).putObject("translations");
            Files.createDirectories(root.resolve("data"));String location="data/"+category+".json";JSON.writeValue(root.resolve(location).toFile(),payload);declaration.put("status","COMPLETE").put("total",rows.size()).put("succeeded",rows.size()).put("file",location).put("sha256",ArtifactFingerprint.sha256(root.resolve(location)));
        }
        JSON.writeValue(root.resolve("snapshot.json").toFile(),snapshot);return root;
    }
    static List<ModEntry> prepareJars(Path folder,GameEnvironment environment)throws Exception {
        var cache=new JarScanCache(folder);cache.begin(environment);var mods=new ArrayList<ModEntry>();var jars=new JarScannerService().scan(folder);cache.retain(jars);
        for(Path jar:jars){var mod=new ModMetadataParser().parse(jar,environment);if(mod!=null)mods.add(mod);cache.put(jar,JarScanCache.hashes(jar).sha512(),mod);}cache.save(environment);return mods;
    }
    public static final class IconArchiveImporter {
        private final AppConfig config;private final DatabaseBuilder db;private final EmbeddingService embedding;
        public IconArchiveImporter(AppConfig config,DatabaseBuilder db,EmbeddingService embedding){this.config=config;this.db=db;this.embedding=embedding;}
        public IconArchiveImporter(AppConfig config,DatabaseBuilder db,EmbeddingService embedding,List<BuildSourceProcessor> ignored){this(config,db,embedding);}
        public KnowledgeBuilder.BuildResult build(BuildRequest request,KnowledgeBuilder.ProgressCallback progress)throws Exception {
            var result=new KnowledgeBuilder.BuildResult();var content=new KnowledgeContentStore(db);var vectors=new VectorStore(db,embedding.getDimension());
            for(var source:request.sources()){content.saveSource(source);new ItemIconStore(db).importPack(source,NativeIconPack.read(Path.of(source.location())),new BuildSourceProcessor.Context(db,content,new ImageAssetStore(config.getDataDir()),vectors,embedding,progress,result));}
            result.embeddings=vectors.count();return result;
        }
    }
}
