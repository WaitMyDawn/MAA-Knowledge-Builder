package yagen.waitmydawn.kb.service;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
import yagen.waitmydawn.kb.config.*;import yagen.waitmydawn.kb.model.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
class FullWebMediaTest {
 @TempDir Path temp;
 @AfterEach void close(){LogSetup.shutdown();}
 static byte[] image(int color,int width,int height)throws Exception{var image=new java.awt.image.BufferedImage(width,height,2);image.setRGB(0,0,0xff000000|color);var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",output);return output.toByteArray();}
 static long count(DatabaseBuilder db,String sql)throws Exception{try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
 @Test void allBodyImagesBeyondFormerLimitsAreCachedAndFailedImagesCanResume()throws Exception{
  var config=AppConfig.forIsolatedRun(false).forBuildTarget(temp);var db=new DatabaseBuilder(config);db.initDatabase();var attempts=new AtomicInteger();var failing=new java.util.concurrent.atomic.AtomicBoolean(true);byte[] png=image(1,2,2);String url="https://example.com/full-media";
  var html=new StringBuilder("<nav><img src='/decoration.png'></nav><article><h1>教程</h1><p>核心组装步骤。");for(int i=0;i<270;i++)html.append("<img src='/img/").append(i).append(".png' alt='组装示意图'>");html.append("</p></article>");
  var web=new WikiScraperService(){@Override public Resource fetchBuildResource(String location,String referer,boolean picture){if(location.equals(url))return new Resource(200,html.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),"text/html",location,null,null);attempts.incrementAndGet();assertFalse(location.contains("decoration"));return new Resource(location.endsWith("269.png")&&failing.get()?503:200,png,"image/png",location,null,null);}};
  try(var embedder=new EmbeddingService(null)){
   var builder=new KnowledgeBuilder(config,db,embedder,List.of(new UrlBuildProcessor(web)));var source=BuildRequest.Source.of(BuildRequest.Kind.URL,url);var request=RuntimeBuildFixtures.withSnapshot(new BuildRequest(List.of(source),BuildRequest.Mode.APPEND),temp);
   builder.build(request,null);assertEquals(270,attempts.get());assertEquals(269,count(db,"SELECT COUNT(*) FROM rag_web_image_reference WHERE status='AVAILABLE'"));assertEquals(1,count(db,"SELECT COUNT(*) FROM rag_web_image_reference WHERE status='FAILED'"));assertEquals(0,count(db,"SELECT COUNT(*) FROM rag_web_image_reference WHERE status='DEFERRED'"));
   failing.set(false);builder.build(request,null);assertEquals(271,attempts.get());assertEquals(270,count(db,"SELECT COUNT(*) FROM rag_web_image_reference WHERE status='AVAILABLE'"));int before=attempts.get();String fingerprint=db.contentFingerprint();builder.build(request,null);assertEquals(before,attempts.get());assertEquals(fingerprint,db.contentFingerprint());new KnowledgeContentStore(db).validate();
  }finally{db.shutdown();}
 }
 @Test void qaPageAcquisitionReusesCapturedHtmlAndPersistsAllPicturesAndLinks()throws Exception{
  var db=DatabaseBuilder.atPath(temp.resolve("qa"));db.initDatabase();var calls=new AtomicInteger();byte[] png=image(2,2,2);String url="https://www.mcmod.cn/item/99000001.html";String html="<article class='item-content common-text'><h1>完整图文教程</h1><p>升级核心操作。<img src='/one.png' alt='升级界面'></p><p>安装芯片。<img src='/two.png' alt='安装步骤'></p><p>激活设备。<img src='/three.png' alt='激活界面'></p></article>";
  var web=new WikiScraperService(){@Override public Resource fetchResource(String location,String referer,boolean picture,Map<String,String> headers){calls.incrementAndGet();return new Resource(200,picture?png:html.getBytes(java.nio.charset.StandardCharsets.UTF_8),picture?"image/png":"text/html",location,null,null);}};web.setResourceDatabase(db);var cache=new PublicKnowledgeCache(db);web.setPublicCache(cache);
  try(var embedder=new EmbeddingService(null)){
   web.fetchBuildResource(url,null,false);cache.put(url,new WikiScraperService.FetchedPage(new WikiScraperService.WebPage("教程",url,"升级核心操作和安装芯片。","mcmod",Map.of()),List.of()));
   var media=new WebPageMediaService(db,embedder,temp,web);web.setPageMediaHandler(media::acquire);web.fetchPageWithRegistries(url,"mcmod");assertEquals(4,calls.get());assertEquals(3,count(db,"SELECT COUNT(*) FROM rag_web_image_reference WHERE status='AVAILABLE'"));assertEquals(3,count(db,"SELECT COUNT(*) FROM rag_image_occurrence"));assertTrue(count(db,"SELECT COUNT(*) FROM rag_chunk_image")>=3);new KnowledgeContentStore(db).validate();
   web.fetchPageWithRegistries(url,"mcmod");assertEquals(4,calls.get());
  }finally{db.shutdown();}
 }
 @Test void ocrHasNoDefaultSixtyFourImageCutoff()throws Exception{
  var db=DatabaseBuilder.atPath(temp.resolve("ocr"));db.initDatabase();String url="https://example.com/ocr-full";var html=new StringBuilder("<article><h1>升级教程</h1><p>安装芯片。");for(int i=0;i<70;i++)html.append("<img src='/").append(i).append(".png' alt='升级芯片界面'>");html.append("</p></article>");var calls=new AtomicInteger();
  var web=new WikiScraperService(){@Override public Resource fetchBuildResource(String location,String referer,boolean picture){try{return new Resource(200,!location.equals(url)?image(Integer.parseInt(java.net.URI.create(location).getPath().replaceAll("[^0-9]","")),64,32):html.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),!location.equals(url)?"image/png":"text/html",location,null,null);}catch(Exception e){throw new IllegalStateException(e);}}};
  try(var embedder=new EmbeddingService(null)){var source=BuildRequest.Source.of(BuildRequest.Kind.URL,url);var content=new KnowledgeContentStore(db);content.saveSource(source);var context=new BuildSourceProcessor.Context(db,content,new ImageAssetStore(temp),new VectorStore(db,384),embedder,null,new KnowledgeBuilder.BuildResult());new UrlBuildProcessor(web,null,new WebImageEnricher(p->{calls.incrementAndGet();return new LocalOcrEngine.Result("升级芯片安装位置","OK","zh-Hans");})).process(source,context);assertEquals(70,calls.get());assertEquals(0,context.result().deferredImageAnalyses);assertTrue(context.result().webOcrChunks>=70);}finally{db.shutdown();}
 }
}
