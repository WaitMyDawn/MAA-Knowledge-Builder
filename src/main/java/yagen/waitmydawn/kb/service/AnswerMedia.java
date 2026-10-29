package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.AnswerArtifacts;
import yagen.waitmydawn.kb.model.BuildRequest;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** 模型只产生受控引用标记，文件路径由本次证据快照解析。 */
public final class AnswerMedia {
    private AnswerMedia() {}
    private static final Pattern MARKER = Pattern.compile("\\[\\[(image|source):([a-zA-Z0-9]+)]]");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]\\r\\n]*]\\([^\\r\\n]*?\\)");
    private static final Pattern CONTROLLED = Pattern.compile("!?\\[[^\\]\\r\\n]*]\\(kb-(?:image|source):[^)]+\\)");
    private static final Pattern IMAGE_REFERENCE=Pattern.compile("!\\[[^\\]\\r\\n]*]\\(kb-image:([a-zA-Z0-9]+)\\)");
    private static final Pattern SOURCE_REFERENCE=Pattern.compile("(?<!!)\\[[^\\]\\r\\n]*]\\(kb-source:([a-zA-Z0-9]+)\\)");
    private static final Pattern CODE=Pattern.compile("(?s)```.*?(?:```|\\z)|~~~.*?(?:~~~|\\z)|`[^`\\r\\n]*`");

    public record Assessment(int candidates,int displayed,int valid,int validIllustrations,int unavailable,int citedSources,int invalidReferences) {
        public static Assessment empty() {return new Assessment(0,0,0,0,0,0,0);}
    }

    /** 保留字符位置，便于渲染和评测共同排除代码示例中的引用。 */
    public static String referenceText(String text) {
        var output=new StringBuilder(Objects.toString(text,""));var matcher=CODE.matcher(output.toString());
        while(matcher.find()) for(int i=matcher.start();i<matcher.end();i++) if(output.charAt(i)!='\n') output.setCharAt(i,' ');
        return output.toString();
    }

    public static Assessment assess(String text,AnswerArtifacts artifacts,Path dataDir) {
        if(artifacts==null) return Assessment.empty();
        var images=new HashMap<String,AnswerArtifacts.Picture>();artifacts.pictures().forEach(p -> images.put(p.id(),p));
        var sources=new HashSet<String>();artifacts.sources().forEach(s -> sources.add(s.id()));
        var usedImages=new HashSet<String>();var usedSources=new HashSet<String>();
        String active=referenceText(text);
        var matcher=IMAGE_REFERENCE.matcher(active);while(matcher.find()) usedImages.add(matcher.group(1));
        usedSources.addAll(citedSourceIds(text,artifacts));
        int valid=0,illustrations=0,unavailable=0,invalid=0;
        for(String id:usedImages) {
            var picture=images.get(id);
            if(picture==null) {invalid++;continue;}
            try {resolveImage(dataDir,picture);valid++;if(!"RECIPE".equals(picture.role())) illustrations++;}
            catch(IOException | IllegalArgumentException e) {unavailable++;}
        }
        int cited=0;
        for(String id:usedSources) if(sources.contains(id)) cited++; else invalid++;
        return new Assessment(images.size(),usedImages.size(),valid,illustrations,unavailable,cited,invalid);
    }

    public static Set<String> citedSourceIds(String text) {
        var result=new HashSet<String>();var matcher=SOURCE_REFERENCE.matcher(referenceText(text));
        while(matcher.find()) result.add(matcher.group(1));
        return Set.copyOf(result);
    }

    public static Set<String> citedSourceIds(String text,AnswerArtifacts artifacts) {
        var ids=new HashSet<>(citedSourceIds(text));
        if(ModpackSources.applies(artifacts)) ids.addAll(ModpackSources.inlineSourceIds(text,artifacts));
        return Set.copyOf(ids);
    }

    /** 在网页级展示合并前保留模型实际引用的块身份，区别于兜底展示的来源。 */
    public static AnswerArtifacts snapshotCitations(String text,AnswerArtifacts artifacts) {
        var ids=new LinkedHashSet<>(citedSourceIds(text,artifacts));
        var allowed=new HashSet<String>();artifacts.sources().forEach(s -> allowed.add(s.id()));
        var matcher=MARKER.matcher(referenceText(text));
        while(matcher.find()) if(matcher.group(1).equals("source") && allowed.contains(matcher.group(2))) ids.add(matcher.group(2));
        ids.retainAll(allowed);
        return new AnswerArtifacts(artifacts.scopeId(),artifacts.scopeLabel(),artifacts.sources(),artifacts.pictures(),List.copyOf(ids));
    }

    public static String bind(String answer, AnswerArtifacts artifacts) {
        String safe = answer == null ? "" : answer;
        safe = CONTROLLED.matcher(safe).replaceAll("");
        safe = IMAGE.matcher(safe).replaceAll("[未提供的图片]");
        var sources = new HashMap<String, AnswerArtifacts.Source>();
        var pictures = new HashMap<String, AnswerArtifacts.Picture>();
        artifacts.sources().forEach(s -> sources.put(s.id(), s)); artifacts.pictures().forEach(p -> pictures.put(p.id(), p));
        if (!sources.isEmpty()) {
            var links = Pattern.compile("(?<!!)\\[([^\\]\\r\\n]+)]\\((https?://[^\\s)]+)\\)").matcher(safe);
            var clean = new StringBuilder();
            var allowed = artifacts.sources().stream().map(AnswerArtifacts.Source::location).collect(java.util.stream.Collectors.toSet());
            while (links.find()) links.appendReplacement(clean, java.util.regex.Matcher.quoteReplacement(allowed.contains(links.group(2)) ? links.group() : links.group(1)));
            links.appendTail(clean); safe=clean.toString();
        }
        var usedImages = new HashSet<String>(); var usedSources = new HashSet<String>();
        var matcher = MARKER.matcher(safe); var out = new StringBuilder();
        var protectedRanges = new ArrayList<int[]>();
        var code=CODE.matcher(safe);
        while(code.find()) protectedRanges.add(new int[]{code.start(),code.end()});
        while (matcher.find()) {
            int position=matcher.start();
            if(protectedRanges.stream().anyMatch(r -> position>=r[0] && position<r[1])) {
                matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(matcher.group())); continue;
            }
            String replacement = "";
            if (matcher.group(1).equals("image")) {
                var picture = pictures.get(matcher.group(2));
                if (picture != null && usedImages.add(picture.id())) replacement = imageMarkdown(picture);
            } else {
                var source = sources.get(matcher.group(2));
                if (source != null) { replacement = sourceMarkdown(source); usedSources.add(source.id()); }
            }
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out); safe = out.toString();
        for (var image : artifacts.pictures()) {
            if (usedImages.contains(image.id()) || java.util.Set.of("ENTITY","ITEM","RECIPE").contains(image.role())) continue;
            var paragraphs = new ArrayList<>(List.of(safe.split("\\n\\s*\\n")));
            int best = -1; double score = 0;
            for (int i = 0; i < paragraphs.size(); i++) {
                String paragraph = paragraphs.get(i);
                if (paragraph.contains("```") || paragraph.strip().startsWith("#") || paragraph.strip().startsWith("![")) continue;
                double value = KnowledgeSearchText.overlap(image.caption() + " " + image.context(), paragraph);
                if (value > score) { score = value; best = i; }
            }
            if (best >= 0 && score >= 0.08) {
                paragraphs.set(best, paragraphs.get(best) + "\n\n" + imageMarkdown(image));
                safe = String.join("\n\n", paragraphs); usedImages.add(image.id());
            }
        }
        // 无模型或未输出引用标记时仍提供可追溯的来源入口。
        if (usedSources.isEmpty() && !artifacts.sources().isEmpty() && !ModpackSources.applies(artifacts)) {
            var labels = new LinkedHashMap<String,String>();
            for (var source : artifacts.sources()) labels.putIfAbsent(source.documentId() + "/" + source.pageNumber(), sourceMarkdown(source));
            safe += "\n\n资料来源：" + String.join(" · ", labels.values().stream().limit(5).toList());
        }
        return ModpackSources.format(safe,artifacts);
    }

    private static String label(String value) { return Objects.toString(value, "").replaceAll("[\\[\\]\\r\\n]", " "); }
    private static String imageMarkdown(AnswerArtifacts.Picture image) {
        return "![" + label(image.caption().isBlank() ? "参考插图" : image.caption()) + "](kb-image:" + image.id() + ")";
    }
    private static String sourceMarkdown(AnswerArtifacts.Source source) {
        return "[" + label(source.title()) + (source.pageNumber() == null ? "" : " · 第 " + source.pageNumber() + " 页") + "](kb-source:" + source.id() + ")";
    }

    public static Path resolveImage(Path dataDir, AnswerArtifacts.Picture image) throws java.io.IOException {
        var store=new ImageAssetStore(dataDir);
        if(!store.available(image.assetId(),image.relativePath())) throw new java.io.IOException("图片缓存缺失或校验失败");
        return store.resolve(image.assetId(),image.relativePath());
    }

    public static void attachRecipes(QaPipeline.QaResult result, RendererService.RenderBatch batch, Path dataDir) throws java.io.IOException {
        attachRecipes(result,batch.images(),dataDir);
        if(result.artifacts==null)return;
        var sources=new ArrayList<>(result.artifacts.sources());var pictures=new ArrayList<AnswerArtifacts.Picture>();
        var cards=new HashMap<String,RendererService.RenderedRecipe>();
        for(var card:batch.cards())cards.put("i"+BuildRequest.fingerprint(card.key()).substring(0,20),card);
        for(var picture:result.artifacts.pictures()) {
            var card=cards.get(picture.id());if(card==null){pictures.add(picture);continue;}
            String sourceId="s"+BuildRequest.fingerprint("recipe:"+card.key()).substring(0,20);
            String evidence=card.summary()+"\n原始配方：\n"+card.recipeJson();if(evidence.length()>24000)evidence=evidence.substring(0,24000);
            String location=card.sourceType().equals("RUNTIME_SNAPSHOT")?"mkb-runtime:"+Objects.toString(card.profileId(),"active")+"/"+card.recipeId():"jar:"+card.sourceMod()+"!/"+card.recipeId();
            if(sources.stream().noneMatch(s -> s.id().equals(sourceId)))sources.add(new AnswerArtifacts.Source(sourceId,"recipe:"+card.fingerprint(),card.recipeId(),location,card.plan().kind().label,null,card.sourceType(),evidence));
            pictures.add(new AnswerArtifacts.Picture(picture.id(),picture.assetId(),picture.relativePath(),card.plan().kind().station+" · "+picture.caption(),sourceId,"RECIPE",List.of(),card.summary()));
        }
        result.artifacts=new AnswerArtifacts(result.artifacts.scopeId(),result.artifacts.scopeLabel(),sources,pictures,result.artifacts.citedSourceIds());
    }
    public static void attachRecipes(QaPipeline.QaResult result, Map<String,String> rendered, Path dataDir) throws java.io.IOException {
        if (rendered.isEmpty()) return;
        var artifacts = result.artifacts == null ? AnswerArtifacts.empty() : result.artifacts;
        var images = new ArrayList<>(artifacts.pictures());
        String answer = result.answer;
        var store = new ImageAssetStore(dataDir);
        for (var entry : rendered.entrySet()) {
            if (images.size() >= 8) break;
            try {
                Path path = Path.of(entry.getValue()).toRealPath();
                if (!path.startsWith(dataDir.resolve("generated").toRealPath()) || Files.size(path) > 32L * 1024 * 1024) continue;
                var asset = store.put(Files.readAllBytes(path));
                String name = entry.getKey().split("#",2)[0];
                var picture = new AnswerArtifacts.Picture("i" + BuildRequest.fingerprint(entry.getKey()).substring(0,20), asset.id(), asset.relativePath(),
                        name + " 配方", null, "RECIPE", List.of(), name);
                images.add(picture);
                String markdown = imageMarkdown(picture);
                int end = answer.indexOf("\n\n", Math.max(0, answer.indexOf(name)));
                if (answer.contains(name) && end >= 0) answer = answer.substring(0,end) + "\n\n" + markdown + answer.substring(end);
                else answer += "\n\n" + markdown;
            } catch(java.io.IOException | IllegalArgumentException ignored) { }
        }
        result.answer = answer;
        result.artifacts = new AnswerArtifacts(artifacts.scopeId(), artifacts.scopeLabel(), artifacts.sources(), images,artifacts.citedSourceIds());
    }
}
