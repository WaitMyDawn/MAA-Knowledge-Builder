package yagen.waitmydawn.kb.service;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.util.Units;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 两步模拟教程只验证图片与页码／步骤关联，不作为游戏事实资料。 */
class MultimodalLocalFixtureTest {
    @TempDir Path temp;
    @AfterEach void logging(){LogSetup.shutdown();}
    @Test void nativePdfPageAndLocalGuideStepRetrieveTheCorrectIllustration()throws Exception {
        Path folder=Files.createDirectory(temp.resolve("fixtures"));
        byte[][] pictures={picture(0xff226644),picture(0xff2244bb)};
        try(var document=new PDDocument()) {
            for(int i=0;i<2;i++) {
                var page=new PDPage();document.addPage(page);
                try(var content=new PDPageContentStream(document,page)) {
                    content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),15);content.newLineAtOffset(40,730);
                    content.showText(i==0?"TEST FIXTURE - GOLEM CORE ASSEMBLY: attach the core to the base.":"TEST FIXTURE - GOLEM UPGRADE: open the upgrade interface and insert a module.");content.endText();
                    content.drawImage(LosslessFactory.createFromImage(document,javax.imageio.ImageIO.read(new ByteArrayInputStream(pictures[i]))),40,430,240,160);
                }
            }
            document.save(folder.resolve("图文验收指南.pdf").toFile());
        }
        try(var document=new XWPFDocument()) {
            document.createParagraph().createRun().setText("模拟验收教程，不代表任何真实整合包的机制。");
            for(int i=0;i<2;i++) {
                var heading=document.createParagraph();heading.setStyle("Heading1");heading.createRun().setText(i==0?"第1步 组装傀儡":"第2步 升级傀儡");
                document.createParagraph().createRun().setText(i==0?"先把核心安装到底座，完成结构组装。":"打开升级界面，将升级模块放入槽位，点击确认按钮。");
                document.createParagraph().createRun().addPicture(new ByteArrayInputStream(pictures[i]),XWPFDocument.PICTURE_TYPE_PNG,"step"+i+".png",Units.toEMU(240),Units.toEMU(160));
            }
            try(var output=Files.newOutputStream(folder.resolve("图文验收指南.docx"))){document.write(output);}
        }
        Path data=Files.createDirectory(temp.resolve("data"));var config=AppConfig.forIsolatedRun(false).forBuildTarget(data);var db=new DatabaseBuilder(config);db.initDatabase();
        LocalOcrEngine ocr=image->new LocalOcrEngine.Result((image.getRGB(0,0)&0xffffff)==0x2244bb?"GOLEM UPGRADE 升级界面 模块 槽位 确认按钮":"GOLEM CORE ASSEMBLY 组装核心 底座","OK","fixture");
        try(var embedding=new EmbeddingService(null)) {
            new KnowledgeBuilder(config,db,embedding,List.of(new DocumentDirectoryProcessor(config,ocr,new VisionDescriptionService(config)))).build(RuntimeBuildFixtures.withSnapshot(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,folder.toString()))),data),null);
            var service=new KnowledgeRetrievalService(db,embedding,data,false);
            var turns=yagen.waitmydawn.kb.eval.GoldenSet.load(Path.of("golden-set-media.json")).conversations().stream().flatMap(c->c.turns().stream()).filter(t->t.category().equals("local-document")).toList();
            String pdfQuestion=turns.getFirst().question(),guideQuestion=turns.getLast().question();
            var pdf=service.retrieve(pdfQuestion,service.resolve(pdfQuestion,null,null),false);
            assertFalse(pdf.artifacts().pictures().isEmpty());
            assertTrue(pdf.artifacts().pictures().stream().allMatch(p->pdf.artifacts().sources().stream().anyMatch(s->s.id().equals(p.sourceId())&&Integer.valueOf(2).equals(s.pageNumber()))));
            var resolution=service.resolve(guideQuestion,null,null);
            var guide=service.retrieve(guideQuestion,resolution,false);
            assertFalse(guide.artifacts().pictures().isEmpty(),()->guide.diagnostics().images()+"\n"+guide.artifacts().sources());
            assertTrue(guide.artifacts().pictures().stream().anyMatch(p->p.context().contains("升级")));
            for(int i=0;i<2;i++) {
                var artifacts=i==0?pdf.artifacts():guide.artifacts();String answer=artifacts.pictures().stream().map(p->"![验收图](kb-image:"+p.id()+")").collect(java.util.stream.Collectors.joining("\n\n"));
                assertTrue(yagen.waitmydawn.kb.eval.RuleJudge.judge(turns.get(i),MultimodalAuditRunner.observation(answer,artifacts,data)).passed(),turns.get(i).id());
            }
            Path export=Path.of("target/p5-local-fixtures");Files.createDirectories(export);
            for(String file:List.of("图文验收指南.pdf","图文验收指南.docx"))Files.copy(folder.resolve(file),export.resolve(file),StandardCopyOption.REPLACE_EXISTING);
        } finally {db.shutdown();}
    }
    private static byte[] picture(int color)throws Exception {
        var image=new BufferedImage(240,160,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        try {g.setColor(new java.awt.Color(color));g.fillRect(0,0,240,160);g.setColor(java.awt.Color.WHITE);g.drawRect(20,30,80,80);g.drawLine(100,70,200,70);}finally{g.dispose();}
        var output=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",output);return output.toByteArray();
    }
}
