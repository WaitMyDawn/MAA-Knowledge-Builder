package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.BuildRequest;
import yagen.waitmydawn.kb.model.KnowledgeContent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import javax.imageio.ImageIO;

/** 本地资料只读取选定目录内的普通文件，解析及索引失败不发布部分基础库。 */
final class DocumentDirectoryProcessor implements BuildSourceProcessor {
    private static final Set<String> FORMATS = Set.of("txt", "doc", "docx", "pdf", "png", "jpg", "jpeg", "gif", "webp");
    private final Path dataDir;
    private final LocalOcrEngine ocr;
    private final VisionDescriptionService vision;

    DocumentDirectoryProcessor(AppConfig config) { this(config, LocalOcrEngine.windows(), new VisionDescriptionService(config)); }
    DocumentDirectoryProcessor(AppConfig config, LocalOcrEngine ocr, VisionDescriptionService vision) {
        dataDir = config.getDataDir().toAbsolutePath().normalize(); this.ocr = ocr; this.vision = vision;
    }
    @Override public boolean supports(BuildRequest.Kind kind) { return kind == BuildRequest.Kind.DOCUMENT_DIRECTORY; }

    @Override public void process(BuildRequest.Source source, Context context) throws Exception {
        Path selected = Path.of(source.location());
        if(NativeIconPack.detected(selected)) {
            new ItemIconStore(context.database()).importPack(source,NativeIconPack.read(selected),context);return;
        }
        Path root = selected.toRealPath(), output = dataDir.toRealPath();
        if (!Files.isDirectory(root) || Files.isSymbolicLink(selected) || root.startsWith(output) || sensitive(root))
            throw new IllegalArgumentException("资料目录不能是敏感目录、符号链接或应用数据目录");
        context.content().setSourceScope(source.id(), "local:" + source.id(), "LOCAL",
                root.getFileName() == null ? root.toString() : root.getFileName().toString(), null);
        var analysisCache = new HashMap<String, Analysis>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                context.checkCancelled();
                if (!directory.equals(root) && (excluded(root, directory) || directory.toRealPath().startsWith(output))) return FileVisitResult.SKIP_SUBTREE;
                if(!directory.equals(root) && NativeIconPack.detected(directory))throw new IOException("发现嵌套导出包，请直接选择该包目录，避免将图标作为普通知识导入："+directory.getFileName());
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                context.checkCancelled();
                if (!attributes.isRegularFile() || excluded(root, file)) return FileVisitResult.CONTINUE;
                Path real = file.toRealPath();
                if (!real.startsWith(root) || real.startsWith(output)) return FileVisitResult.CONTINUE;
                String format = extension(file);
                if (!FORMATS.contains(format)) {
                    context.content().saveImportReport(source.id(), real.toString(), format, "UNSUPPORTED", "未支持的文件格式", null);
                    context.result().warnings.add("暂不支持的资料文件：" + root.relativize(file));
                    return FileVisitResult.CONTINUE;
                }
                try { importFile(real, format, source, context, analysisCache); }
                catch (java.util.concurrent.CancellationException e) { throw e; }
                catch (Exception e) { throw new IOException("资料解析失败：" + root.relativize(file) + "（" + e.getClass().getSimpleName() + "）；原库保留", e); }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    void processDocument(BuildRequest.Source source,String location,Context context)throws Exception {
        Path file=Path.of(location).toRealPath(),root=Path.of(source.location()).toRealPath();
        if(!file.startsWith(root)||!Files.isRegularFile(file)||Files.isSymbolicLink(Path.of(location))||Files.isSymbolicLink(Path.of(source.location()))
                ||sensitive(file)||excluded(root,Path.of(location))||file.startsWith(dataDir.toRealPath()))throw new IOException("文档不在可读取的来源目录内");
        String name=file.getFileName().toString();int dot=name.lastIndexOf('.');
        if(dot<0)throw new IOException("文档没有扩展名");
        importFile(file,name.substring(dot+1).toLowerCase(Locale.ROOT),source,context,new HashMap<>());
    }
    private void importFile(Path file, String format, BuildRequest.Source source, Context context,
                            Map<String, Analysis> analysisCache) throws Exception {
        long max = format.equals("txt") ? 8L * 1024 * 1024 : 64L * 1024 * 1024;
        if (Files.size(file) > max) throw new IOException("文件大小超限");
        byte[] bytes;
        try (var input = Files.newInputStream(file)) { bytes = input.readNBytes((int)max + 1); }
        if (bytes.length > max) throw new IOException("文件大小超限");
        importBytes(file.toString(), file.getFileName().toString(), format, bytes, source, context, analysisCache);
    }

    void importBytes(String location, String title, String format, byte[] bytes, BuildRequest.Source source,
                     Context context, Map<String, Analysis> analysisCache) throws Exception {
        try(var timingScope=BuildTrace.phase("document.parse",title+" ["+format+"]")) {

        if (!FORMATS.contains(format)) throw new IOException("不支持的文档格式");
        if (bytes.length > (format.equals("txt") ? 8L : 64L) * 1024 * 1024) throw new IOException("文档大小超限");
        String documentId = BuildRequest.fingerprint(location);
        context.visitedDocuments().add(documentId);
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        if (context.content().hasDocument(documentId)) {
            if(context.refresh()&&(context.reparse()||!hash.equals(context.content().documentHash(documentId)))) {
                new SourceLifecycleStore(context.database()).clearDocumentContent(documentId);
            } else if(!hash.equals(context.content().documentHash(documentId))) {
                context.result().conflicts++;
                context.result().warnings.add("文件内容已变化，补充构建保留原版本："+location+"；更新已有内容请使用完整重建");
                return;
            } else {
                context.result().reusedDocuments++;
                context.content().linkDocumentSource(documentId, source.id());
                return;
            }
        }
        context.content().saveDocument(new KnowledgeContent.Document(documentId, source.id(), title,
                location, hash, mediaType(format)));
        context.content().linkDocumentSource(documentId, source.id());
        var writer = new Writer(location, title, documentId, context, analysisCache);
        switch (format) {
            case "txt" -> {
                var headings=new HeadingHierarchy();var paragraph=new StringBuilder();
                for(String line:decodeText(bytes).split("\\R")) {
                    var marker=java.util.regex.Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(line);
                    if(marker.matches() || line.isBlank()) {
                        if(!paragraph.isEmpty()) {writer.accept(new DocumentBlock(paragraph.toString(),headings.leaf(),null,"NATIVE",List.of(),headings.path(),headings.lineage(),"PARAGRAPH",null));paragraph.setLength(0);}
                        if(marker.matches()) headings.accept(marker.group(1).length(),marker.group(2));
                    } else paragraph.append(line).append('\n');
                }
                if(!paragraph.isEmpty()) writer.accept(new DocumentBlock(paragraph.toString(),headings.leaf(),null,"NATIVE",List.of(),headings.path(),headings.lineage(),"PARAGRAPH",null));
            }
            case "docx" -> new WordDocumentReader().readDocx(bytes, writer::accept);
            case "doc" -> new WordDocumentReader().readDoc(bytes, writer::accept);
            case "pdf" -> new PdfDocumentReader().read(bytes, writer::accept);
            default -> writer.accept(new DocumentBlock("", "图片", null, "IMAGE",
                    List.of(new DocumentBlock.Picture(bytes, title, "原始图片", null, "STANDALONE"))));
        }
        writer.finish();
        new SourceLifecycleStore(context.database()).parsedDocument(documentId,source.kind());
        String status = writer.blocks == 0 ? "EMPTY" : writer.partial ? "PARTIAL" : "INDEXED";
        context.content().saveImportReport(source.id(), location, format, status,
                writer.partial ? "部分图片未完成内容识别，已保留原图和来源" : null, documentId);
        if (writer.blocks > 0) context.result().documents++;
        if (writer.partial) context.result().warnings.add("部分图像内容未识别：" + title);
        if (context.progress() != null) context.progress().onProgress("documents", context.result().documents, 0,
                "已处理：" + title + "（" + status + "）");
        }
    }

    record Analysis(String ocrText, String description, String status, boolean partial) {}

    private Analysis analyze(BufferedImage image, byte[] png, Context context) throws Exception {
        String text = "", description = "", status;
        boolean partial = false;
        int limit = Math.max(0, Integer.getInteger("maa.documents.maxOcrImages", 128));
        if (context.result().ocrAttempts >= limit) { status = "OCR_DEFERRED"; partial = true; }
        else {
            context.result().ocrAttempts++;
            LocalOcrEngine.Result result;
            try(var ocrTiming=BuildTrace.step("document.ocr","本地文字识别")) { result = ocr.recognize(image); }
            catch (java.util.concurrent.CancellationException e) { throw e; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException("OCR cancelled"); }
            catch (Exception e) { result = new LocalOcrEngine.Result("", "UNAVAILABLE", ""); }
            text = result.text(); status = result.status().equals("OK") && text.isBlank() ? "OCR_NO_TEXT" : "OCR_" + result.status();
            if (!result.status().equals("OK")) partial = true;
            if (!text.isBlank()) context.result().ocrImages++;
        }
        if (vision.enabled()) {
            int maxVision = Math.max(0, Integer.getInteger("maa.documents.maxVisionImages", 16));
            if (context.result().visionAttempts >= maxVision) { status += ";VISION_DEFERRED"; partial = true; }
            else {
                context.checkCancelled(); context.result().visionAttempts++;
                try(var visionTiming=BuildTrace.step("document.vision","已启用的图像描述")) { description = vision.describe(png); status += ";VISION_OK"; context.result().visionImages++; }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException("Vision request cancelled"); }
                catch (java.util.concurrent.CancellationException e) { throw e; }
                catch (Exception e) { status += ";VISION_FAILED"; partial = true; }
            }
        } else status += ";VISION_DISABLED";
        if (text.isBlank() && description.isBlank()) partial = true;
        return new Analysis(text, description, status, partial);
    }

    private final class Writer {
        private final String location;
        private final String title;
        private final String documentId;
        private final Context context;
        private final Map<String, Analysis> cache;
        private int blocks, ordinal, pictures;
        private long characters;
        private boolean partial;
        private final List<KnowledgeContent.Block> rawBlocks=new ArrayList<>();
        private final Map<String,List<String>> blockImages=new HashMap<>();
        Writer(String location, String title, String documentId, Context context, Map<String, Analysis> cache) {
            this.location = location; this.title = title; this.documentId = documentId; this.context = context; this.cache = cache;
        }

        void accept(DocumentBlock block) throws Exception {
            context.checkCancelled();
            String text = block.text().strip();
            var occurrences = new ArrayList<String>();
            String method = block.method();
            for (var picture : block.pictures()) {
                context.checkCancelled();
                if (picture.role().equals("ILLUSTRATION") && !picture.caption().isBlank()) text += "\n图注：" + picture.caption();
                if (++pictures > 512) throw new IOException("单文件图片数量超过 512");
                String occurrenceId = documentId + "/image/" + pictures;
                try {
                    var asset = context.images().put(picture.bytes());
                    String status = "NATIVE_TEXT_ONLY";
                    if (!picture.role().equals("PAGE_PREVIEW")) {
                        Analysis analysis = cache.get(asset.id());
                        if (analysis == null) {
                            BufferedImage image = ImageIO.read(new ByteArrayInputStream(picture.bytes()));
                            if (image == null) throw new IOException("无法解码图片");
                            analysis = analyze(image, PdfDocumentReader.png(image), context); cache.put(asset.id(), analysis);
                        }
                        status = analysis.status(); partial |= analysis.partial();
                        asset = new KnowledgeContent.ImageAsset(asset.id(), asset.relativePath(), asset.mediaType(), asset.width(), asset.height(),
                                analysis.ocrText().isBlank() ? null : analysis.ocrText(), analysis.description().isBlank() ? null : analysis.description());
                        text = mergeText(text, analysis.ocrText());
                        if (!analysis.ocrText().isBlank()) method = block.method().equals("SCAN") || block.method().equals("IMAGE") ? "OCR" : "NATIVE+OCR";
                        if (!analysis.description().isBlank()) { text += "\n模型图像描述（推断）：" + analysis.description(); method += "+VISION"; }
                    }
                    context.content().saveImage(asset);
                    context.content().saveOccurrence(new KnowledgeContent.ImageOccurrence(occurrenceId, documentId, asset.id(),
                            picture.caption(), location, block.page(), pictures - 1));
                    context.content().setOccurrenceMetadata(occurrenceId, picture.role(), picture.position(), picture.bounds(), status);
                    occurrences.add(occurrenceId); context.result().localImages++;
                } catch (IOException | IllegalArgumentException e) {
                    if (picture.role().equals("STANDALONE") || picture.role().equals("SCAN_PAGE")) throw e;
                    partial = true; context.result().warnings.add("无法提取插图：" + title + " / " + picture.position());
                }
            }
            if (text.isBlank() && !occurrences.isEmpty()) {
                text = "图片文件：" + title + "。未识别到文字或图像描述，仅保存图片及来源信息。";
                method = "METADATA_ONLY"; partial = true;
            }
            if (text.isBlank()) return;
            if ((characters += text.length()) > 8L * 1024 * 1024) throw new IOException("提取文字超过 8 MiB");
            for(String paragraph:text.split("\\n\\s*\\n")) {
                if(paragraph.isBlank()) continue;
                if(ordinal>=20000) throw new IOException("单文件原文块超过 20000");
                String id=documentId+"/block/"+ordinal;
                var raw=new KnowledgeContent.Block(id,documentId,block.headingPath(),block.lineage(),ordinal++,block.page(),block.kind(),block.tableId(),paragraph,method);
                context.content().saveBlock(raw);rawBlocks.add(raw);blockImages.put(id,List.copyOf(occurrences));
            }
            blocks++;
        }

        void finish() throws Exception {
            var vectors = new ArrayList<TextChunker.TextChunk>();
            var bound=new HashSet<String>();var index=new GuideImageIndex(context.database());
            for(var raw:rawBlocks)for(String occurrence:blockImages.getOrDefault(raw.id(),List.of()))if(bound.add(occurrence)) {
                try(var c=context.database().getConnection();var p=c.prepareStatement("SELECT o.image_id,o.caption,a.ocr_text,o.analysis_status FROM rag_image_occurrence o JOIN rag_image_asset a ON a.id=o.image_id WHERE o.id=?")) {
                    p.setString(1,occurrence);try(var r=p.executeQuery()){if(r.next())index.bind(occurrence,documentId,r.getString(1),r.getString(2),r.getString(3),r.getString(4),raw.ordinal());}
                }
            }
            int chunkOrdinal=0;
            for(var indexed:StructuredChunker.split(rawBlocks,title,null,title,"local_document",location,context.embedder())) {
                context.checkCancelled();var chunk=indexed.chunk();String id=chunk.id();
                context.content().saveChunk(new KnowledgeContent.Chunk(id,documentId,chunk.text(),indexed.heading(),indexed.page(),chunkOrdinal++));
                context.content().setChunkMethod(id,indexed.method());
                for(String blockId:indexed.blockIds()) {
                    context.content().linkBlock(id,blockId);
                    for(String occurrence:blockImages.getOrDefault(blockId,List.of())) context.content().saveLink(new KnowledgeContent.ChunkImageLink(id,occurrence,indexed.page()==null?"same_paragraph":"same_page"));
                }
                vectors.add(chunk);
            }
            context.vectors().storeBatch(vectors, context.embedder());
            context.result().textChunks += vectors.size(); context.result().embeddings += vectors.size();
        }
    }

    static String mergeText(String nativeText, String ocrText) {
        if (ocrText.isBlank()) return nativeText;
        String nativeNormalized = nativeText.replaceAll("\\s", ""), ocrNormalized = ocrText.replaceAll("\\s", "");
        if (!nativeNormalized.isEmpty() && ocrNormalized.contains(nativeNormalized)) return ocrText;
        if (!ocrNormalized.isEmpty() && nativeNormalized.contains(ocrNormalized)) return nativeText;
        var lines = new LinkedHashMap<String, String>();
        for (String line : (nativeText + "\n" + ocrText).split("\\R"))
            if (!line.isBlank()) lines.putIfAbsent(line.replaceAll("\\s", ""), line.strip());
        return String.join("\n", lines.values());
    }

    static String decodeText(byte[] bytes) throws CharacterCodingException {
        if (bytes.length >= 2 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe) return decode(bytes, 2, StandardCharsets.UTF_16LE);
        if (bytes.length >= 2 && bytes[0] == (byte)0xfe && bytes[1] == (byte)0xff) return decode(bytes, 2, StandardCharsets.UTF_16BE);
        int offset = bytes.length >= 3 && bytes[0] == (byte)0xef && bytes[1] == (byte)0xbb && bytes[2] == (byte)0xbf ? 3 : 0;
        if (offset == 3) return decode(bytes, offset, StandardCharsets.UTF_8);
        for (byte value : bytes) if (value == 0) throw new CharacterCodingException();
        try { return decode(bytes, offset, StandardCharsets.UTF_8); }
        catch (CharacterCodingException e) { return decode(bytes, 0, Charset.forName("GB18030")); }
    }
    private static String decode(byte[] bytes, int offset, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length-offset)).toString();
    }
    private static String mediaType(String format) {
        return switch (format) {
            case "txt" -> "text/plain"; case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pdf" -> "application/pdf"; case "jpg", "jpeg" -> "image/jpeg"; default -> "image/" + format;
        };
    }
    private static String extension(Path file) { String name = file.getFileName().toString(); int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot+1).toLowerCase(Locale.ROOT); }
    private static boolean sensitive(Path path) {
        for (Path part : path) if (Set.of("secrets", "credentials").contains(part.toString().toLowerCase(Locale.ROOT).replaceFirst("^\\.+", ""))) return true;
        return false;
    }
    private static boolean excluded(Path root, Path file) {
        if (sensitive(file)) return true;
        for (Path part : root.relativize(file)) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.startsWith(".") || name.startsWith("~$") || Set.of("node_modules", "target", "build",
                    "credentials.txt", "secrets.txt", "passwords.txt", "id_rsa", "id_ed25519").contains(name)) return true;
        }
        return false;
    }
}
