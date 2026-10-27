package yagen.waitmydawn.kb.ui;

import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import yagen.waitmydawn.kb.model.AnswerArtifacts;
import yagen.waitmydawn.kb.service.AnswerMedia;
import yagen.waitmydawn.kb.service.AnswerSourceLinks;
import yagen.waitmydawn.kb.service.ModpackSources;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** 受控图片与来源卡片按正文顺序展示；回放只读取随消息保存的快照。 */
public final class AnswerRenderer {
    private AnswerRenderer() {}
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]\\r\\n]*]\\(kb-image:([a-zA-Z0-9]+)\\)");
    private static final Pattern SOURCE = Pattern.compile("\\[[^\\]\\r\\n]*]\\(kb-source:([a-zA-Z0-9]+)\\)");
    private static final Pattern FENCES=Pattern.compile("(?s)```.*?(?:```|\\z)|~~~.*?(?:~~~|\\z)");

    public static Node render(String markdown, AnswerArtifacts artifacts, Path dataDir) {
        if (artifacts == null) return MarkdownRenderer.render(markdown, List.of(dataDir.resolve("generated")));
        markdown=ModpackSources.format(markdown,artifacts);
        boolean modpack=ModpackSources.applies(artifacts);
        var container = new VBox(8);
        if (artifacts.scopeLabel()!=null) container.getChildren().add(new Label("资料范围："+artifacts.scopeLabel()));
        var sources = new HashMap<String, AnswerArtifacts.Source>(); artifacts.sources().forEach(s -> sources.put(s.id(), s));
        var pictures = new HashMap<String, AnswerArtifacts.Picture>(); artifacts.pictures().forEach(p -> pictures.put(p.id(), p));
        var matcher = IMAGE.matcher(AnswerMedia.referenceText(markdown)); int end = 0;
        while (matcher.find()) {
            appendText(container, markdown.substring(end, matcher.start()), sources, dataDir,modpack);
            var picture = pictures.get(matcher.group(1));
            if (picture != null) container.getChildren().add(picture(picture, modpack?null:sources.get(picture.sourceId()), dataDir));
            else container.getChildren().add(new Label("图片引用不可用，正文仍可阅读。"));
            end = matcher.end();
        }
        appendText(container, markdown == null ? "" : markdown.substring(end), sources, dataDir,modpack);
        if(modpack && !sources.isEmpty()) {
            var evidence=new VBox(8);
            for(var source:artifacts.sources()) {
                var excerpt=new TextArea(source.evidence());excerpt.setEditable(false);excerpt.setWrapText(true);excerpt.setPrefRowCount(3);
                evidence.getChildren().add(new VBox(4,new Label(Objects.toString(source.heading(),"正文")),excerpt));
            }
            var details=new TitledPane("查看检索依据（"+sources.size()+" 个文本块）",evidence);details.setExpanded(false);
            container.getChildren().add(details);
        }
        return container;
    }

    private static void appendText(VBox container, String text, Map<String,AnswerArtifacts.Source> sources, Path dataDir,boolean modpack) {
        var code=FENCES.matcher(text);int end=0;
        while(code.find()) {
            appendParagraphs(container,text.substring(end,code.start()),sources,dataDir,modpack);
            container.getChildren().add(MarkdownRenderer.render(code.group(),List.of(dataDir.resolve("generated"))));
            end=code.end();
        }
        appendParagraphs(container,text.substring(end),sources,dataDir,modpack);
    }

    private static void appendParagraphs(VBox container,String text,Map<String,AnswerArtifacts.Source> sources,Path dataDir,boolean modpack) {
        String active=AnswerMedia.referenceText(text);int cursor=0;
        for (String paragraph : text.split("\\n\\s*\\n")) {
            if (paragraph.isBlank()) continue;
            int start=text.indexOf(paragraph,cursor);cursor=start+paragraph.length();
            var matcher = SOURCE.matcher(active.substring(start,cursor)); var refs = new LinkedHashSet<String>(); var cleaned = new StringBuilder();int offset=0;
            while (matcher.find()) {
                refs.add(matcher.group(1));cleaned.append(paragraph,offset,matcher.start());offset=matcher.end();
            }
            cleaned.append(paragraph.substring(offset));
            String body=modpack?cleaned.toString().replaceAll("(?m)^[ \\t]*[-*][ \\t]*$",""):cleaned.toString();
            if (!body.isBlank()) container.getChildren().add(MarkdownRenderer.render(body, List.of(dataDir.resolve("generated"))));
            var links = new FlowPane(8, 4);
            for (String id : refs) if (sources.containsKey(id)) {
                var source=sources.get(id);
                var link=modpack?SourceLinks.create(source.title(),source.location()):sourceLink(source);
                if(modpack) link.getStyleClass().add("modpack-source-link");
                links.getChildren().add(link);
            }
            if (!links.getChildren().isEmpty()) container.getChildren().add(links);
        }
    }

    private static Node picture(AnswerArtifacts.Picture picture, AnswerArtifacts.Source source, Path dataDir) {
        var box = new VBox(5);
        box.getStyleClass().add("answer-picture");
        String caption = picture.caption() == null || picture.caption().isBlank() ? "参考插图" : picture.caption();
        try {
            Path file = AnswerMedia.resolveImage(dataDir, picture);
            var image = new Image(file.toUri().toString());
            if (image.isError() || image.getWidth() <= 0) throw new IllegalArgumentException("图片无法解码");
            var view = new ImageView(image); view.setPreserveRatio(true); view.setSmooth(!"RECIPE".equals(picture.role()));
            double preferred = "RECIPE".equals(picture.role()) ? Math.max(260, image.getWidth()) : Math.max(96, image.getWidth());
            double widthLimit = "RECIPE".equals(picture.role()) ? 1040 : 640;
            view.fitWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                    () -> Math.min(preferred, Math.min(widthLimit, Math.max(120, box.getWidth() - 12))), box.widthProperty()));
            view.setStyle("-fx-cursor: hand;"); view.setOnMouseClicked(event -> preview(image, caption));
            box.getChildren().add(view);
        } catch (Exception missing) { box.getChildren().add(new Label("图片缓存不可用：" + caption + "。可继续阅读说明和来源。")); }
        String role = switch (Objects.toString(picture.role(), "")) { case "SCAN_PAGE" -> "扫描页 · "; case "PAGE_PREVIEW" -> "整页预览 · "; default -> ""; };
        var label = new Label(role + caption); label.setWrapText(true); label.getStyleClass().add("markdown-muted");
        box.getChildren().add(label);
        if(picture.grounding()!=null) {
            var grounding=picture.grounding();String inference=Objects.equals(grounding.basis(),"CONTEXT_INFERRED")?"（按上下文推定）":"";
            String anchor=Objects.toString(grounding.stepText(),"未确定对应步骤");
            if(anchor.isBlank())anchor="未确定对应步骤";
            var step=new Label(yagen.waitmydawn.kb.service.GuideImageSemantics.label(grounding.purpose())+inference+" · "+(anchor.length()>180?anchor.substring(0,180)+"…":anchor));
            step.setWrapText(true);step.getStyleClass().add("markdown-muted");step.setTooltip(new Tooltip("用途依据："+grounding.basis()+"\n图片文字识别："+grounding.analysisStatus()+"\n"+anchor));box.getChildren().add(step);
        }
        if (source != null) box.getChildren().add(sourceLink(source));
        return box;
    }

    private static Hyperlink sourceLink(AnswerArtifacts.Source source) {
        String method = source.method() == null ? "" : source.method();
        String suffix = method.contains("VISION") ? " · 含模型推断" : method.contains("OCR") ? " · 含OCR识别" : method.equals("METADATA_ONLY") ? " · 仅文件信息" : "";
        var link = new Hyperlink(source.title() + (source.pageNumber() == null ? "" : " · 第 " + source.pageNumber() + " 页") + suffix);
        link.setWrapText(true); link.setTooltip(new Tooltip(Objects.toString(source.heading(), "") + "\n" + source.location()));
        link.setOnAction(event -> {
            var dialog = new Dialog<Void>(); dialog.setTitle("资料来源"); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            var text = new TextArea(source.evidence()); text.setEditable(false); text.setWrapText(true); text.setPrefSize(660, 320);
            var location = new Label(source.location()); location.setWrapText(true);
            var contents = new VBox(8, new Label(source.title()), location, text);
            var copy=new Button("复制来源位置");
            copy.setOnAction(copyEvent -> javafx.scene.input.Clipboard.getSystemClipboard().setContent(Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT,source.location())));
            contents.getChildren().add(copy);
            if (AnswerSourceLinks.safeUrl(source.location())) contents.getChildren().add(SourceLinks.create("打开原网页", source.location()));
            dialog.getDialogPane().setContent(contents); dialog.setResizable(true); dialog.show();
        });
        return link;
    }

    private static void preview(Image image, String caption) {
        var dialog = new Dialog<Void>(); dialog.setTitle(caption); dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        var view = new ImageView(image); view.setPreserveRatio(true);
        var zoom = new Slider(0.1, 4, Math.min(1, 850 / image.getWidth()));
        view.fitWidthProperty().bind(zoom.valueProperty().multiply(image.getWidth()));
        var scroll = new ScrollPane(new StackPane(view)); scroll.setPannable(true); scroll.setPrefViewportWidth(850); scroll.setPrefViewportHeight(560);
        dialog.getDialogPane().setContent(new VBox(8, new Label(caption), scroll, new HBox(8, new Label("缩放"), zoom)));
        dialog.show();
    }
}
