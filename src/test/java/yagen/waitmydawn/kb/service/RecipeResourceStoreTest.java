package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class RecipeResourceStoreTest {
    @TempDir Path temp;
    @Test void tagsAreNamespacedRecursiveAndReplaceConflictsFailClosed() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var tags=new HashMap<String,List<RecipeResourceStore.TagFile>>();
        tags.put("a:wood",List.of(new RecipeResourceStore.TagFile("a",json.readTree("{\"values\":[\"a:oak\",\"#a:more\",{\"id\":\"#missing:optional\",\"required\":false}]}"))));
        tags.put("a:more",List.of(new RecipeResourceStore.TagFile("a",json.readTree("{\"values\":[\"b:oak\"]}"))));
        assertEquals(List.of("a:oak","b:oak"),RecipeResourceStore.resolveTag("a:wood",tags).items());
        tags.put("a:more",List.of(new RecipeResourceStore.TagFile("a",json.readTree("{\"values\":[\"#a:wood\"]}"))));assertFalse(RecipeResourceStore.resolveTag("a:wood",tags).resolved());
        tags.put("a:more",List.of(new RecipeResourceStore.TagFile("a",json.readTree("{\"replace\":true,\"values\":[\"a:one\"]}")),new RecipeResourceStore.TagFile("b",json.readTree("{\"values\":[\"b:one\"]}"))));
        assertTrue(RecipeResourceStore.resolveTag("a:more",tags).problem().contains("replace"));
    }
    @Test void exactModelReferencesKeepNestedPathsAndExcludeBlockFacesCustomAndAnimatedModels() throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("base"));db.initDatabase();
        new VectorStore(db,384);
        var png=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);png.setRGB(8,8,0xff00dd00);var bytes=new java.io.ByteArrayOutputStream();ImageIO.write(png,"png",bytes);
        var files=new LinkedHashMap<String,byte[]>();
        files.put("assets/demo/models/item/tools/widget.json","{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"demo:other/real_texture\"}}".getBytes(StandardCharsets.UTF_8));
        files.put("assets/demo/textures/other/real_texture.png",bytes.toByteArray());
        files.put("assets/demo/models/item/block.json","{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"demo:other/real_texture\"}}".getBytes(StandardCharsets.UTF_8));
        files.put("assets/demo/models/item/custom.json","{\"loader\":\"demo:custom\",\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"demo:other/real_texture\"}}".getBytes(StandardCharsets.UTF_8));
        files.put("data/demo/tags/items/a.json","{\"values\":[\"demo:tools/widget\"]}".getBytes(StandardCharsets.UTF_8));
        files.put("data/demo/tags/item/b.json","{\"values\":[\"#demo:a\"]}".getBytes(StandardCharsets.UTF_8));
        files.put("assets/demo/lang/zh_cn.json","{\"item.demo.tools/widget\":\"测试工具\"}".getBytes(StandardCharsets.UTF_8));
        Path jar=temp.resolve("demo.jar");try(var out=new JarOutputStream(Files.newOutputStream(jar))){for(var file:files.entrySet()){out.putNextEntry(new JarEntry(file.getKey()));out.write(file.getValue());out.closeEntry();}}
        try {
            var result=new RecipeResourceStore(db).importJar(jar,"demo",temp);assertEquals(2,result.tags());assertEquals(1,result.flatIcons());
            var snapshot=new ItemIconService(db,temp).snapshot();assertEquals("JAR_FLAT",snapshot.item("demo:tools/widget",false).source());assertEquals("测试工具",snapshot.name("demo:tools/widget"));
            assertEquals("MISSING",snapshot.item("demo:block",false).source());assertEquals("MISSING",snapshot.item("demo:custom",false).source());assertEquals("MISSING",snapshot.item("other:tools/widget",false).source());
            String before=db.contentFingerprint();assertEquals(new RecipeResourceStore.Imported(0,0,0),new RecipeResourceStore(db).importJar(jar,"demo",temp));assertEquals(before,db.contentFingerprint());
            new KnowledgeContentStore(db).validate();
        }finally{db.shutdown();}
    }
    @Test void localizedLookupPrefersWholeNamesAndNeverAddsUnaskedEnglishCategories() throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("base"));db.initDatabase();
        try {
            var store=new RecipeResourceStore(db);store.importLanguage("{\"item.demo.sword\":\"钻石剑\",\"item.demo.diamond\":\"钻石\",\"item.demo.stone\":\"Stone\",\"item.demo.redstone\":\"Redstone\"}","zh_cn","demo");
            assertEquals(List.of("demo:sword"),store.findItemIds("钻石剑怎么合成？"));assertEquals(List.of("demo:redstone"),store.findItemIds("How to craft redstone?"));assertTrue(store.findItemIds("怎么合成工作台").isEmpty());
            store.importLanguage("{\"item.demo.book\":\"书\",\"item.demo.bookshelf\":\"书架\"}","zh_cn","demo");
            assertEquals(List.of("demo:book"),store.findItemIds("书怎么合成？"));assertEquals(List.of("demo:bookshelf"),store.findItemIds("书架怎么合成？"));
        }finally{db.shutdown();}
    }
}
