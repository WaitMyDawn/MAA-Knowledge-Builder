package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import yagen.waitmydawn.kb.model.AnswerArtifacts;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DialogueFocusTest {
    @Test void patchesOneAxisAndRestoresFromSerializedHistory()throws Exception {
        var object=new AnswerArtifacts.FocusedObject("test:fire_dragon","ENTITY","火龙",Map.of("variant","红色","sex","sex=male","growth_stage","growth_stage=5"));
        var artifacts=new AnswerArtifacts(null,null,List.of(),List.of(),List.of(),new AnswerArtifacts.TurnState("profile",null,List.of(object)));
        var json=new ObjectMapper();var restored=json.readValue(json.writeValueAsString(artifacts),AnswerArtifacts.class);
        String expanded=DialogueFocus.expand("改成绿色，其他条件不变。",restored.turnState(),"profile",null);
        assertTrue(expanded.contains("绿色"));assertTrue(expanded.contains("growth_stage=5"));assertTrue(expanded.contains("sex=male"));assertFalse(expanded.contains("红色"));
        assertEquals("改成绿色",DialogueFocus.expand("改成绿色",restored.turnState(),"different",null));
        assertNull(json.readValue("{\"sources\":[],\"pictures\":[]}",AnswerArtifacts.class).turnState());
        var incomplete=new RuntimeEvidencePolicy.Snapshot("p","1.21.1","neoforge",Map.of(),false);
        assertTrue(RuntimeEvidencePolicy.context(incomplete).contains("安装成员清单缺失"));
        var legacy=json.readValue("{\"profile\":\"p\",\"mods\":{\"example\":\"1\"}}",RuntimeEvidencePolicy.Snapshot.class);
        assertTrue(legacy.membershipComplete());
    }
    @Test void narrowsOnlyTheLatestObjectSet(){
        var state=new AnswerArtifacts.TurnState("p",null,List.of(new AnswerArtifacts.FocusedObject("test:fire_dragon","ENTITY","火龙",Map.of()),new AnswerArtifacts.FocusedObject("test:ice_dragon","ENTITY","冰龙",Map.of())));
        String query=DialogueFocus.expand("只看冰龙五阶雌性。",state,"p",null);
        assertTrue(query.contains("test:ice_dragon"));assertFalse(query.contains("test:fire_dragon"));assertTrue(query.contains("sex=female"));assertTrue(query.contains("growth_stage=5"));
        assertEquals("铁傀儡如何建造",DialogueFocus.expand("铁傀儡如何建造",state,"p",null));
    }
    @Test void actionsNeedEvidenceBeyondAttributesAndNegativeMentions(){
        assertFalse(TaskEvidence.missing("如何驯服它", "entity attack_damage=10").isEmpty());
        assertFalse(TaskEvidence.missing("如何驯服它", "未找到使用物品喂食的条件。").isEmpty());
        assertTrue(TaskEvidence.missing("如何驯服它", "手持指定食物，右键喂食直到成功。").isEmpty());
        assertTrue(TaskEvidence.missing("钻石剑怎么合成", "本地配方").isEmpty());
        assertFalse(TaskEvidence.missing("这个实体有什么特点和用途", "基础生命值20，攻击力5").isEmpty());
        assertFalse(TaskEvidence.missing("它有哪些获取方式", "仅确认注册名").isEmpty());
    }
}
