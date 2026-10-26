package yagen.waitmydawn.kb.service;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.xwpf.usermodel.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StructuredKnowledgeTest {
    @TempDir Path temp;

    @Test void htmlKeepsEmptyAncestorHeadingsRepeatedSectionsRowHeadersAndNodeOrder() {
        var parsed=WebDocumentParser.parse(Jsoup.parse("""
                <article><h1>全流程</h1><h2>T3</h2><h3>装备</h3><div>第一段<p>第二段</p>第三段</div>
                <table><tr><th>材料</th><th>数量</th></tr><tr><th scope='row' rowspan='2'>铁锭</th><td>8</td></tr><tr><td>16</td></tr></table>
                <h2>T4</h2><h3>装备</h3><p>第四阶段装备。</p>
                <h2>番外</h2><span class='common-text-title common-text-title-3'>巫法</span><h4>仪式</h4><p>仪式需要足够能量。</p></article>
                """),"https://example.com/guide");
        assertEquals(List.of("第一段","第二段","第三段"),parsed.blocks().stream().limit(3).map(WebDocumentParser.Block::text).toList());
        assertEquals("全流程 > T3 > 装备",parsed.blocks().getFirst().headingPath());
        var rows=parsed.blocks().stream().filter(b -> b.kind().equals("TABLE_ROW")).toList();
        assertEquals(2,rows.size());assertTrue(rows.getFirst().text().contains("表头：材料 | 数量"));
        assertTrue(rows.getFirst().text().contains("铁锭 | 8"));assertTrue(rows.getLast().text().contains("铁锭 | 16"));
        var t4=parsed.blocks().stream().filter(b -> b.text().equals("第四阶段装备。")).findFirst().orElseThrow();
        assertNotEquals(parsed.blocks().getFirst().lineage(),t4.lineage());
        assertEquals("全流程 > 番外 > 巫法 > 仪式",parsed.blocks().getLast().headingPath());
    }

    @Test void smallParagraphsMergeButPagesMethodsAndTableRowsKeepTheirBoundaries() {
        try(var embedding=new EmbeddingService(null)) {
            var blocks=List.of(raw("a","d","T3 > 组装","/root/s1/s2/",0,1,"PARAGRAPH",null,"先制作核心。","NATIVE"),
                    raw("b","d","T3 > 组装","/root/s1/s2/",1,1,"PARAGRAPH",null,"再安装外壳。","NATIVE"),
                    raw("c","d","T3 > 组装","/root/s1/s2/",2,2,"PARAGRAPH",null,"第二页内容。","NATIVE"),
                    raw("d","d","T3 > 组装","/root/s1/s2/",3,2,"PARAGRAPH",null,"OCR内容。","OCR"));
            var chunks=StructuredChunker.split(blocks,"标题",null,"资料","local_document","file",embedding);
            assertEquals(3,chunks.size());assertEquals(List.of("a","b"),chunks.getFirst().blockIds());
            assertTrue(chunks.getFirst().chunk().text().contains("先制作核心。\n\n再安装外壳。"));
            var row=raw("row","d","T3 > 装备","/root/s1/s3/",4,2,"TABLE_ROW","table1",
                    "表头：材料 | 数量 | 备注\n表格行：钢锭 | 8 | "+"保持足够能量后再进行操作。".repeat(60)+"😀","TABLE");
            var parts=StructuredChunker.split(List.of(row),"标题",null,"资料","web","url",embedding);
            assertTrue(parts.size()>1);
            for(var part:parts) {
                assertTrue(embedding.tokenCount(part.chunk().text())<=StructuredChunker.TOKEN_LIMIT);
                assertTrue(part.chunk().text().contains("表头：材料 | 数量 | 备注"));
                assertTrue(part.chunk().text().contains("行首（原文）：表格行：钢锭 | 8"));
                assertFalse(Character.isHighSurrogate(part.chunk().text().charAt(part.chunk().text().length()-1)));
                assertEquals(List.of("row"),part.blockIds());
            }
        }
    }

    @Test void expansionKeepsStepsAndTableContextButCannotCrossScopePageOrOcrBoundary() throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("base"));db.initDatabase();
        try {
            var content=new KnowledgeContentStore(db);var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/a");content.saveSource(source);
            content.saveDocument(new KnowledgeContent.Document("d",source.id(),"指南",source.location(),"hash","text/html"));
            content.saveDocument(new KnowledgeContent.Document("other",source.id(),"其他指南","https://example.com/b","hash2","text/html"));
            var blocks=List.of(raw("a","d","傀儡 > 组装","/root/s1/s2/",0,1,"PARAGRAPH",null,"第一步制作身体。","NATIVE"),
                    raw("b","d","傀儡 > 组装","/root/s1/s2/",1,1,"PARAGRAPH",null,"第二步安装手臂。","NATIVE"),
                    raw("c","d","傀儡 > 升级","/root/s1/s3/",2,1,"TABLE_ROW","t1","表头：材料 | 数量\n表格行：钻石 | 2","TABLE"),
                    raw("badpage","d","傀儡 > 组装","/root/s1/s2/",3,2,"PARAGRAPH",null,"第二页排除文本","NATIVE"),
                    raw("badocr","d","傀儡 > 组装","/root/s1/s2/",4,1,"PARAGRAPH",null,"OCR排除文本","OCR"),
                    raw("badscope","other","傀儡 > 组装","/root/s1/s2/",0,1,"PARAGRAPH",null,"其他来源排除文本","NATIVE"));
            blocks.forEach(content::saveBlock);content.saveChunk(new KnowledgeContent.Chunk("seed","d","制作身体","傀儡 > 组装",1,0));content.linkBlock("seed","a");
            var hit=new VectorStore.SearchResult("seed","制作身体",null,"指南","web",0.8,source.location());
            var original=new AnswerArtifacts.Source("s"+BuildRequest.fingerprint("seed").substring(0,20),"d","指南",source.location(),"傀儡 > 组装",1,"NATIVE","制作身体");
            var result=new KnowledgeContextBuilder(db).build(List.of(hit),List.of(original),"傀儡组装升级");
            String evidence=result.sources().stream().map(AnswerArtifacts.Source::evidence).collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(evidence.contains("第一步制作身体。\n\n第二步安装手臂。"));assertTrue(evidence.contains("钻石 | 2"));
            assertFalse(evidence.contains("排除文本"));assertEquals(Set.of("a","b","c"),new HashSet<>(result.diagnostics().blockIds()));
            assertTrue(result.sources().stream().anyMatch(s -> s.id().equals(result.diagnostics().chunkSources().get("seed"))));
            content.validate();
        } finally {db.shutdown();}
    }

    @Test void oversizedParagraphUsesTheMatchingTailExcerptAndReportsTruncation() throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("large"));db.initDatabase();
        try {
            var content=new KnowledgeContentStore(db);var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/a");content.saveSource(source);
            content.saveDocument(new KnowledgeContent.Document("d",source.id(),"大段落",source.location(),"hash","text/html"));
            content.saveBlock(raw("a","d","操作","/root/s1/",0,null,"PARAGRAPH",null,"前面的无关说明".repeat(1000)+"目标在尾部，安装核心即可。","NATIVE"));
            content.saveChunk(new KnowledgeContent.Chunk("seed","d","目标在尾部，安装核心即可。","操作",null,0));content.linkBlock("seed","a");
            var hit=new VectorStore.SearchResult("seed","目标在尾部，安装核心即可。",null,"大段落","web",0.8,source.location());
            var origin=new AnswerArtifacts.Source("s"+BuildRequest.fingerprint("seed").substring(0,20),"d","大段落",source.location(),"操作",null,"NATIVE",hit.chunkText());
            var context=new KnowledgeContextBuilder(db).build(List.of(hit),List.of(origin),"安装核心");
            assertTrue(context.sources().getFirst().evidence().contains("目标在尾部"));
            assertFalse(context.sources().getFirst().evidence().contains("前面的无关说明"));
            assertTrue(context.sources().getFirst().method().contains("EXCERPT"));assertTrue(context.diagnostics().truncated());
            assertTrue(context.diagnostics().characters()<=context.diagnostics().budget());
        } finally {db.shutdown();}
    }

    @Test void wordStylesAndOriginalFirstTableRowSurviveExtraction() throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();
        try(var doc=new XWPFDocument()) {
            var root=doc.createParagraph();root.setStyle("Heading1");root.createRun().setText("阶段");
            var heading=doc.createParagraph();heading.setStyle("Heading2");heading.createRun().setText("装备");
            doc.createParagraph().createRun().setText("先加工材料。");
            var table=doc.createTable(2,2);table.getRow(0).getCell(0).setText("材料");table.getRow(0).getCell(1).setText("数量");
            table.getRow(1).getCell(0).setText("铁锭");table.getRow(1).getCell(1).setText("8");doc.write(bytes);
        }
        var blocks=new ArrayList<DocumentBlock>();new WordDocumentReader().readDocx(bytes.toByteArray(),blocks::add);
        assertTrue(blocks.stream().allMatch(b -> b.headingPath().equals("阶段 > 装备")));
        assertTrue(blocks.getLast().text().contains("首行（原文）：材料 | 数量"));
        assertTrue(blocks.getLast().text().contains("铁锭 | 8"));assertNull(blocks.getLast().page());
    }

    @Test void genericParentDoesNotExpandUnrelatedSiblingsAndTheBudgetRemainsHard() throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("budget"));db.initDatabase();String previous=System.getProperty("maa.qa.contextMaxChars");
        try {
            System.setProperty("maa.qa.contextMaxChars","1000");
            var content=new KnowledgeContentStore(db);var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/a");content.saveSource(source);
            content.saveDocument(new KnowledgeContent.Document("d",source.id(),"指南",source.location(),"hash","text/html"));
            content.saveBlock(raw("a","d","发育思路 > 小技巧","/root/s1/s2/",0,null,"PARAGRAPH",null,"星河术法可以制作法杖。","NATIVE"));
            content.saveBlock(raw("unrelated","d","发育思路 > 其他地区","/root/s1/s3/",1,null,"PARAGRAPH",null,"不应扩展的其他地区说明。","NATIVE"));
            content.saveChunk(new KnowledgeContent.Chunk("seed","d","星河术法可以制作法杖。","发育思路 > 小技巧",null,0));content.linkBlock("seed","a");
            var hit=new VectorStore.SearchResult("seed","星河术法可以制作法杖。",null,"指南","web",0.8,source.location());
            var original=new AnswerArtifacts.Source("s"+BuildRequest.fingerprint("seed").substring(0,20),"d","指南",source.location(),"发育思路 > 小技巧",null,"NATIVE",hit.chunkText());
            var context=new KnowledgeContextBuilder(db).build(List.of(hit),List.of(original),"星河术法怎么玩");
            assertEquals(List.of("a"),context.diagnostics().blockIds());
            for(int i=1;i<15;i++) content.saveBlock(raw("extra"+i,"d","发育思路 > 小技巧","/root/s1/s2/",i+2,null,"PARAGRAPH",null,"安装核心后再运行设备。".repeat(8)+i,"NATIVE"));
            context=new KnowledgeContextBuilder(db).build(List.of(hit),List.of(original),"小技巧");
            assertTrue(context.diagnostics().truncated());assertTrue(context.diagnostics().characters()<=1000);
            assertTrue(context.sources().stream().noneMatch(s -> s.evidence().contains("其他地区")));
        } finally {
            if(previous==null) System.clearProperty("maa.qa.contextMaxChars");else System.setProperty("maa.qa.contextMaxChars",previous);
            db.shutdown();
        }
    }

    @Test void rawBlocksAndLinksParticipateInAtomicPublicationAndFingerprinting() throws Exception {
        var base=DatabaseBuilder.atPath(temp.resolve("base-copy"));base.initDatabase();new VectorStore(base,384);
        var staging=DatabaseBuilder.atPath(temp.resolve("staging-copy"));staging.initDatabase();new VectorStore(staging,384);
        try {
            var content=new KnowledgeContentStore(staging);var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/a");content.saveSource(source);
            content.saveDocument(new KnowledgeContent.Document("d",source.id(),"指南",source.location(),"hash","text/html"));
            content.saveChunk(new KnowledgeContent.Chunk("seed","d","组装操作。","组装",null,0));
            content.saveBlock(raw("a","d","组装","/root/s1/",0,null,"PARAGRAPH",null,"组装操作。","NATIVE"));content.linkBlock("seed","a");
            content.validate();base.replaceBuildDataFrom(staging);assertEquals(staging.contentFingerprint(),base.contentFingerprint());
            content.linkBlock("seed","missing");assertThrows(IllegalStateException.class,content::validate);
            new KnowledgeContentStore(base).validate();
        } finally {base.shutdown();staging.shutdown();}
    }

    private static KnowledgeContent.Block raw(String id,String doc,String path,String lineage,int ordinal,Integer page,String kind,String table,String text,String method) {
        return new KnowledgeContent.Block(id,doc,path,lineage,ordinal,page,kind,table,text,method);
    }
}
