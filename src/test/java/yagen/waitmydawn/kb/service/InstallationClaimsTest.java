package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 安装结论必须按快照成员事实校正，网页资料不能被说成已安装。 */
class InstallationClaimsTest {
    private static final List<String> INSTALLED=List.of("minecraft","neoforge","iceandfire","create","irons_spellbooks");

    @Test void webOnlyModsAreNotInstalledAndInstalledModsAreNotEmpty(){
        String answer="当前快照为 1.21.1 / NeoForge，已安装 `cataclysm`（灾变）。已安装 `create`。未安装 cataclysm。";
        var conflicts=InstallationClaims.conflicts(answer,INSTALLED);
        assertEquals(1,conflicts.size());
        assertEquals("cataclysm",conflicts.getFirst().token());
        assertEquals("未安装",conflicts.getFirst().truth());
        String repaired=InstallationClaims.repair(answer,conflicts);
        assertTrue(repaired.contains("未安装 `cataclysm`"));
        assertTrue(repaired.contains("安装事实按当前快照校正"));
        assertTrue(repaired.contains("已安装 `create`"));
        assertTrue(InstallationClaims.conflicts(repaired,INSTALLED).isEmpty());
    }

    @Test void installedModsMarkedMissingAreCorrectedTheSameWay(){
        var conflicts=InstallationClaims.conflicts("该环境未安装 iceandfire，因此火龙不存在。",INSTALLED);
        assertEquals(1,conflicts.size());
        assertEquals("已安装",conflicts.getFirst().truth());
        assertTrue(InstallationClaims.repair("该环境未安装 iceandfire，因此火龙不存在。",conflicts).contains("已安装 iceandfire"));
    }

    @Test void chineseOnlyAndUnrelatedSentencesAreLeftAlone(){
        for(String answer:List.of("当前环境未安装灾变。","已安装 20 个模组。","已安装 minecraft 与 neoforge。","已安装 Java 21。"))
            assertTrue(InstallationClaims.conflicts(answer,INSTALLED).isEmpty(),answer);
    }

    @Test void possessiveDisplayNameIsNotMistakenForANamespace(){
        assertTrue(InstallationClaims.conflicts("已安装 Alex's Mobs。",List.of("alexsmobs")).isEmpty());
        assertTrue(InstallationClaims.conflicts("当前未安装 Alex's Mobs。",List.of("alexsmobs")).isEmpty());
    }
}
