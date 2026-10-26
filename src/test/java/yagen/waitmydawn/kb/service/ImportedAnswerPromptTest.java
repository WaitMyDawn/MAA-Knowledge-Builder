package yagen.waitmydawn.kb.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import yagen.waitmydawn.kb.agent.AnswerAgent;
import yagen.waitmydawn.kb.dto.QuestionType;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ImportedAnswerPromptTest {
    @Test void documentFactsAreNotLabeledAsNameOnlyTierAndGapsStayRetrievalBounded() {
        var captured=new AtomicReference<ChatRequest>();
        var model=new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                captured.set(request);return ChatResponse.builder().aiMessage(AiMessage.from("本轮检索未找到足够依据。[[source:s1]]")).build();
            }
        };
        var service=new RagAgentService(null) { @Override public ChatModel getChatModel() {return model;} };
        var agent=new AnswerAgent(service);
        String answer=agent.compose(new AnswerAgent.AnswerContext("Goety入门流程",QuestionType.GENERAL,List.of(),List.of(),
                List.of(new VectorStore.SearchResult("c1","黑暗魔杖需要缠魂木",null,"指南","web",0.5,"https://example.com/guide"),
                        new VectorStore.SearchResult("legacy","独立的本地模组说明",null,"模组","mcmod",0.5,"https://example.com/mod")),
                null,List.of(),null,"整合包范围","来源 [[source:s1]]：黑暗魔杖需要缠魂木"));
        assertNotNull(captured.get());assertTrue(answer.startsWith("本轮检索"));
        String prompt=captured.get().messages().stream().filter(m -> m instanceof UserMessage).map(m -> ((UserMessage)m).singleText()).reduce("",String::concat);
        assertTrue(prompt.contains("Data Tier: IMPORTED EVIDENCE"));assertFalse(prompt.contains("Data Tier: TIER C"));
        assertEquals(1,prompt.split("黑暗魔杖需要缠魂木",-1).length-1);
        assertTrue(prompt.contains("独立的本地模组说明"));
        String system=captured.get().messages().getFirst().toString();
        assertTrue(system.contains("本轮检索未找到足够依据"));assertTrue(system.contains("equipment strength rankings"));
    }
}
