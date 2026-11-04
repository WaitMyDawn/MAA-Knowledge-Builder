package yagen.waitmydawn.kb.service;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.listener.*;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.ModelProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.dto.QaMetrics;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PromptCostTest {
    @TempDir Path temp;
    @Test void mixedPromptHasDisjointTextBucketsAndSeparateSchema(){
        var request=ChatRequest.builder().messages(SystemMessage.from("Rules\n[MKB_EVIDENCE]\nCurrent environment\n[MKB_HISTORY]\nEarlier question and answer\n[MKB_END_HISTORY]\nCurrent fact"),
                ToolExecutionResultMessage.from("call","query_runtime","{\"value\":42}"),
                UserMessage.from("[Recent conversation — reference resolution only]\nEarlier answer\n[Imported evidence and allowed references]\n采集材料\n[User Question]\n怎么采集？"))
                .toolSpecifications(AgentToolLoop.specifications()).build();
        var cost=PromptCostEstimator.measure(request);
        for(String kind:List.of("system","evidence","history","tool_results","question","tool_schema"))assertTrue(cost.estimatedTokens().getOrDefault(kind,0L)>0,kind);
        long text=request.messages().stream().mapToLong(message->message instanceof SystemMessage s?s.text().length():message instanceof ToolExecutionResultMessage t?t.text().length():((UserMessage)message).singleText().length()).sum();
        long schemas=request.toolSpecifications().stream().mapToLong(spec->spec.toString().length()).sum();assertEquals(text+schemas,cost.characters().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(1,cost.requests());assertEquals(0,cost.nonTextParts());
    }
    @Test void retriesFailuresAndCrossThreadCallbacksKeepReportedUsageIndependent()throws Exception {
        var service=new RagAgentService(null);service.beginTrace();var field=RagAgentService.class.getDeclaredField("usageListener");field.setAccessible(true);var listener=(ChatModelListener)field.get(service);
        var request=ChatRequest.builder().messages(SystemMessage.from("Instructions"),UserMessage.from("question")).build();
        var attrs=new HashMap<Object,Object>();listener.onRequest(new ChatModelRequestContext(request,ModelProvider.OPEN_AI,attrs));
        var response=ChatResponse.builder().aiMessage(AiMessage.from("answer")).tokenUsage(new TokenUsage(100,12)).build();
        var worker=new Thread(()->listener.onResponse(new ChatModelResponseContext(response,request,ModelProvider.OPEN_AI,attrs)));worker.start();worker.join();
        var failed=new HashMap<Object,Object>();listener.onRequest(new ChatModelRequestContext(request,ModelProvider.OPEN_AI,failed));listener.onError(new ChatModelErrorContext(new IllegalStateException("fixture"),request,ModelProvider.OPEN_AI,failed));
        var usage=service.usageTrace();assertEquals(2,usage.calls);assertEquals(100,usage.inputTokens);assertEquals(12,usage.outputTokens);assertEquals(1,usage.unknownUsageCalls);assertEquals(2,usage.promptCost.requests());assertNotEquals(100,usage.promptCost.estimatedTotal());
        service.endTrace();
    }
    @Test void historyAndCsvExposeEstimatesAndOldMetricsStayUnknown(){
        var metrics=new QaMetrics();metrics.promptCost=PromptCostEstimator.measure(ChatRequest.builder().messages(UserMessage.from("采集方法")).build());metrics.tokenEstimateInput=100;
        var history=new MetricsHistoryService(temp);history.save(metrics);var restored=history.loadAll().getFirst();assertEquals(metrics.promptCost,restored.promptCost);assertEquals(100,restored.tokenEstimateInput);assertTrue(restored.toUiSummary().contains("本地估算"));
        assertNull(new QaMetrics().promptCost);assertEquals(QaMetrics.csvHeader().split(",",-1).length,metrics.toCsvRow().split(",",-1).length);assertEquals(QaMetrics.csvHeader().split(",",-1).length,new QaMetrics().toCsvRow().split(",",-1).length);
    }
}
