package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeHybridRankerTest {
    private KnowledgeHybridRanker.Chunk chunk(String id,String heading,String body,int ordinal,double semantic) {
        return new KnowledgeHybridRanker.Chunk(id,"d1","玩法指南",heading,"标题：样本指南\n章节："+heading+"\n"+body,ordinal,"NATIVE",semantic,0);
    }

    @Test void groundedBilingualAliasesFindChineseInstructionsAndDoNotLetRegistryListsDominate() {
        var corpus=new ArrayList<KnowledgeHybridRanker.Chunk>();
        corpus.add(chunk("a","起步","远古术法 (AetherWorks) 从制作法杖开始。先收集辉光木，再将其加工为能量棱镜；收集材料后安装棱镜并右击祭坛即可启动装置。",0,0.1));
        corpus.add(chunk("b","起步","将棱镜安装到法杖上，然后在工作台合成充能器。打开充能器的设置面板，把输入槽和输出槽分别设为顶部和底部，最后连接能源。",1,0.1));
        corpus.add(new KnowledgeHybridRanker.Chunk("config","d2","实体配置翻译","正文","aetherworks:wight aetherworks:boss aetherworks:mage aetherworks:altar aetherworks:spirit",0,"NATIVE",0.9,0));
        for(int i=0;i<12;i++) corpus.add(chunk("mention"+i,"简介"+i,"远古术法 (AetherWorks) 详见玩法章节。",i+10,0.8));
        var result=KnowledgeHybridRanker.rank("AetherWorks怎么玩",corpus);
        assertTrue(result.aliases().stream().anyMatch(a -> a.equivalent().equals("远古术法") && a.chunkId().equals("a")));
        assertTrue(result.selected().stream().anyMatch(r -> r.chunk().id().equals("a")));
        assertTrue(result.selected().stream().anyMatch(r -> r.chunk().id().equals("b")));
        assertFalse(result.selected().stream().anyMatch(r -> r.chunk().id().equals("config")));
        var english=KnowledgeHybridRanker.rank("How do I play AetherWorks?",corpus);
        assertEquals(List.of("alias:aetherworks"),english.queryTerms());
        assertTrue(english.selected().stream().anyMatch(r -> r.chunk().id().equals("b")));
        var config=KnowledgeHybridRanker.rank("AetherWorks配置有哪些注册名",corpus).candidates().stream().filter(r -> r.chunk().id().equals("config")).findFirst().orElseThrow();
        assertEquals(1,config.signals().quality());
    }

    @Test void ambiguousEnglishNamesDoNotProduceUnprovenCrossLanguageAliases() {
        var result=KnowledgeHybridRanker.rank("Core怎么玩",List.of(
                chunk("a","简介","星河核心 (Core) 提供基础能源。",0,0.5),chunk("b","简介","炼金核心 (Core) 用于药剂合成。",1,0.5)));
        assertTrue(result.aliases().isEmpty());assertEquals(List.of("core"),result.queryTerms());
    }

    @Test void keywordAndVectorBranchesBothContributeWithoutTreatingAHighCosineAsProof() {
        var result=KnowledgeHybridRanker.rank("星核装配",List.of(
                chunk("keyword","装配","星核装配需要先放置核心，再右击装置安装部件，安装顺序必须保持核心、支架、外壳，完成后接入能源。",0,0.01),
                chunk("semantic","运行步骤","先将关键组件放入机器内部，再将外壳安装到支架上，随后启动电源并检查运行情况。",1,0.9)));
        assertEquals("keyword",result.selected().getFirst().chunk().id());
        assertTrue(result.candidates().stream().anyMatch(r -> r.chunk().id().equals("keyword") && r.signals().keywordRank()>0 && r.signals().semanticRank()==0));
        assertTrue(result.candidates().stream().anyMatch(r -> r.chunk().id().equals("semantic") && r.signals().keywordRank()==0 && r.signals().semanticRank()>0));
    }

    @Test void duplicatesAreRemovedButDifferentNumbersAndNegationsAreRetained() {
        var result=KnowledgeHybridRanker.rank("装备升级方法",List.of(
                chunk("a","装备升级","装备升级需要消耗2个材料，先将材料放入升级台的左侧，再把装备放入右侧，点击确认后即可升级。",0,0.7),
                chunk("copy","装备升级","装备升级需要消耗2个材料，先将材料放入升级台的左侧，再把装备放入右侧，点击确认后即可升级。",1,0.7),
                chunk("number","装备升级","装备升级需要消耗3个材料，先将材料放入升级台的左侧，再把装备放入右侧，点击确认后即可升级。",2,0.7),
                chunk("negation","装备升级","装备升级不能消耗2个材料，先将材料放入升级台的左侧，再把装备放入右侧，点击确认后即可升级。",3,0.7)));
        assertEquals(3,result.selected().size());
        assertTrue(result.candidates().stream().anyMatch(r -> r.decision().equals("DUPLICATE")));
        assertTrue(result.selected().stream().anyMatch(r -> r.chunk().id().equals("number")));
        assertTrue(result.selected().stream().anyMatch(r -> r.chunk().id().equals("negation")));
    }

    @Test void compositeRecommendationReservesEquipmentWeaponAndAccessoryCandidates() {
        var corpus=new ArrayList<KnowledgeHybridRanker.Chunk>();
        corpus.add(chunk("stage","T3：","进入第三阶段后可以制作新的战斗装备。",0,0.1));
        corpus.add(chunk("armor","装备","厚铁护甲提供额外的护甲和韧性，适合正面作战，需要在铁砧上将厚铁锭与基础护甲合成，升级后还会获得额外生命值。",1,0.2));
        corpus.add(chunk("weapon","武器","月光长剑能够造成持续伤害，适合需要远程牵制的敌人，完成主线任务后可以在祭坛上使用月光石制作。",2,0.2));
        corpus.add(chunk("accessory","饰品","风铃能够增加移动速度和跳跃高度，需要放在腰部饰品栏，击败山谷守卫后完成任务即可获得。",3,0.2));
        for(int i=0;i<20;i++) corpus.add(chunk("intro"+i,"简介"+i,"武器装备饰品推荐：此处将推荐不同阶段的武器装备和饰品，详情见后面的任务书。",20+i,0.9));
        var result=KnowledgeHybridRanker.rank("T3的装备+武器+饰品推荐",corpus);
        var selected=result.selected().stream().map(r -> r.chunk().id()).toList();
        assertTrue(selected.containsAll(List.of("armor","weapon","accessory")),selected.toString());
    }
}
