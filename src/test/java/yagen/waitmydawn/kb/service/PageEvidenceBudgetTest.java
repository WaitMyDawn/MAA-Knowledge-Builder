package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PageEvidenceBudgetTest {
    @Test void neighborContextPreservesConditionsWithoutCrossingSectionsOrChangingIdentity() {
        var before = new VectorStore.SearchResult("before", "标题：龙\n章节：战斗\n龙在落地后进入栖息状态。", "minecraft", "Minecraft", "mcwiki", .2, "url");
        var anchor = new VectorStore.SearchResult("anchor", "标题：龙\n章节：战斗\n这时弹射物不能伤害它。", "minecraft", "Minecraft", "mcwiki", .9, "url");
        var other = new VectorStore.SearchResult("other", "标题：龙\n章节：掉落\n死亡经验", "minecraft", "Minecraft", "mcwiki", .1, "url");
        var expanded = PageEvidenceBudget.withNeighbors(List.of(before, anchor, other), List.of(anchor)).getFirst();
        assertEquals(anchor.chunkId(), expanded.chunkId());
        assertTrue(expanded.chunkText().contains("落地后"));
        assertTrue(expanded.chunkText().contains("这时弹射物"));
        assertFalse(expanded.chunkText().contains("死亡经验"));
    }

    @Test void longSectionDoesNotEvictIndependentStepsAndSingleSectionCanStillFillBudget() {
        var chunks=new ArrayList<VectorStore.SearchResult>();
        for(int i=0;i<12;i++) chunks.add(new VectorStore.SearchResult("a"+i,"标题：龙\n章节：行为\n攻击事实"+i,"minecraft","Minecraft","mcwiki",1-i*.01,"url"));
        var drops=new VectorStore.SearchResult("drops","标题：龙\n章节：掉落\n掉落事实","minecraft","Minecraft","mcwiki",.5,"url");
        var spawn=new VectorStore.SearchResult("spawn","标题：龙\n章节：生成\n生成事实","minecraft","Minecraft","mcwiki",.4,"url");
        chunks.add(drops);chunks.add(spawn);
        var selected=PageEvidenceBudget.select(chunks,10);
        assertEquals(10,selected.size());assertTrue(selected.contains(drops));assertTrue(selected.contains(spawn));
        assertEquals(10,PageEvidenceBudget.select(chunks.subList(0,12),10).size());
    }
}
