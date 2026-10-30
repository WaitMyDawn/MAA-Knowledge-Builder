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
        var matches = IMAGE.matcher(AnswerMedia.referenceText(markdown)).results().toList();
        var gallery = new LinkedHashMap<String,AnswerArtifacts.Picture>();
        for (var match : matches) if (pictures.containsKey(match.group(1))) gallery.putIfAbsent(match.group(1),pictures.get(match.group(1)));
        var shown = new HashSet<String>(); int end = 0;
        for (int i=0;i<matches.size();i++) {
            var match=matches.get(i);
            appendText(container, markdown.substring(end, match.start()), sources, dataDir,modpack);
            var picture=pictures.get(match.group(1)); end=match.end();
            if (picture==null) {container.getChildren().add(new Label("图片引用不可用，正文仍可阅读。"));continue;}
            if (!shown.add(picture.id())) continue;
            var group=new ArrayList<AnswerArtifacts.Picture>();group.add(picture);
            while (compact(picture) && i+1<matches.size()) {
                var next=matches.get(i+1);var candidate=pictures.get(next.group(1));
                if (!markdown.substring(end,next.start()).isBlank() || candidate==null || !compact(candidate)) break;
                i++;end=next.end();if(shown.add(candidate.id()))group.add(candidate);
            }
            if(group.size()>1) {
                var row=new FlowPane(12,12);row.getStyleClass().add("answer-picture-grid");
                row.prefWrapLengthProperty().bind(container.widthProperty());
                for(var entry:group) {
                    var card=picture(entry,sources.get(entry.sourceId()),dataDir,modpack,List.copyOf(gallery.values()),sources);
                    card.prefWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                            () -> {double width=Math.max(120,container.getWidth());int columns=width>=980?3:width>=580?2:1;return Math.max(120,(width-12*(columns-1))/columns);},container.widthProperty()));
                    card.setMinWidth(0);card.setMaxWidth(Region.USE_PREF_SIZE);row.getChildren().add(card);
                }
                container.getChildren().add(row);
                if(group.stream().anyMatch(p->"ENTITY".equals(p.role()))) {
                    var note=new Label("各图独立缩放，画面占比不代表实际体型比例；相同外观可能对应不同状态。");
                    note.setWrapText(true);note.getStyleClass().add("markdown-muted");container.getChildren().add(note);
                }
            } else container.getChildren().add(picture(picture,sources.get(picture.sourceId()),dataDir,modpack,List.copyOf(gallery.values()),sources));
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

    private static boolean compact(AnswerArtifacts.Picture picture) {return Set.of("ENTITY","ITEM").contains(Objects.toString(picture.role(),""));}

    private static VBox picture(AnswerArtifacts.Picture picture, AnswerArtifacts.Source source, Path dataDir, boolean modpack,
                                List<AnswerArtifacts.Picture> gallery, Map<String,AnswerArtifacts.Source> sources) {
        var box = new VBox(5);
        box.setMinWidth(0);
        box.getStyleClass().add("answer-picture");
        String caption = picture.caption() == null || picture.caption().isBlank() ? "参考插图" : picture.caption();
        try {
            Path file = AnswerMedia.resolveImage(dataDir, picture);
            var image = new Image(file.toUri().toString());
            if (image.isError() || image.getWidth() <= 0) throw new IllegalArgumentException("图片无法解码");
            var view = new ImageView(image); view.setPreserveRatio(true); view.setSmooth(!Set.of("RECIPE","ENTITY","ITEM").contains(Objects.toString(picture.role(),"")));
            double preferred = "RECIPE".equals(picture.role()) ? Math.max(260, image.getWidth()) : Math.max(96, image.getWidth());
            double widthLimit = "RECIPE".equals(picture.role()) ? 1040 : 640;
            view.fitWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                    () -> Math.min(preferred, Math.min(widthLimit, Math.max(120, box.getWidth() - 12))), box.widthProperty()));
            view.setFitHeight(compact(picture)?300:"RECIPE".equals(picture.role())?900:440);
            Runnable open=()->AnswerImageViewer.show(box,gallery,picture.id(),sources,dataDir);
            view.setStyle("-fx-cursor: hand;"); view.setOnMouseClicked(event -> open.run());
            view.setAccessibleText(caption+"，点击放大");
            box.getChildren().add(view);
            var expand=new Button("放大查看");expand.getStyleClass().add("answer-picture-expand");expand.setOnAction(event->open.run());
            box.getChildren().add(expand);
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
        if (source != null && !modpack) box.getChildren().add(sourceLink(source));
        else if(source==null && AnswerSourceLinks.safeUrl(picture.originalUrl())) box.getChildren().add(SourceLinks.create("打开图片来源",picture.originalUrl()));
        return box;
    }

    static Hyperlink sourceLink(AnswerArtifacts.Source source) {
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
            else if(AnswerImageViewer.isLocalLocation(source.location()))contents.getChildren().add(AnswerImageViewer.localSource(source));
            AnswerImageViewer.configureDialog(dialog,link);
            dialog.getDialogPane().setContent(contents); dialog.setResizable(true); dialog.show();
        });
        return link;
    }

}
