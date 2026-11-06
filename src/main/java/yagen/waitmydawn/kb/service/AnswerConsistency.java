package yagen.waitmydawn.kb.service;

import java.util.*;

/**
 * 结构化证据与最终答案的一致性收尾。
 *
 * <p>两个已复现的问题：题目问获取途径时只写命中的渠道，漏掉已核对但未命中的掉落/交易渠道；
 * 渠道只出现在“未读取/未确认”的句子里时，规则的肯定文本会整句丢弃该渠道。
 * 这里只做“补一行核对渠道”的确定性收尾，不删除或改写模型正文（删除会连事实一起丢，
 * 且模型措辞每轮不同）。与已取记录冲突的缺失断言仅记录日志，交由提示词和后续评审处理。
 */
final class AnswerConsistency {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnswerConsistency.class);
    private AnswerConsistency() {}

    private static final String MISSING="(?:未给出|未确认|无法确认|未提供|没有给出|未说明|未包含|无法给出)";
    private static final String FACET="(?:概率|权重|池|数量|条目|掉落|配方|材料|属性|数值|概率性)";
    private static final List<String> CHANNELS=List.of("炼金锅","炼金大锅","工作台","合成","战利品","宝箱","流浪商人","交易","掉落","酿造","注液","熔炉","烧炼");

    static String repair(QaPipeline.QaResult result,String question){
        String answer=result==null?null:result.answer;
        if(answer==null||answer.isBlank())return answer;
        var acquired=new ArrayList<String>();
        if(!result.toolRecipes.isEmpty())acquired.add("配方");
        if(!result.tradeIds.isEmpty())acquired.add("交易");
        if(!result.lootIds.isEmpty())acquired.add("战利品表");
        if(acquired.isEmpty())return answer;
        reportConflicts(answer,acquired);
        var extra=new ArrayList<String>();
        String note=channelNote(answer,question,result);
        if(!note.isBlank())extra.add(note);
        String coverage=channelCoverage(answer,question);
        if(!coverage.isBlank())extra.add(coverage);
        return extra.isEmpty()?answer:answer+"\n\n"+String.join("\n\n",extra);
    }

    /** 只报告，不修改正文：删除整句会把同句里的事实一并丢掉。 */
    private static void reportConflicts(String answer,List<String> acquired){
        for(String clause:answer.split("[。！？；;\\r\\n]")){
            boolean missing=clause.matches("(?s).*"+MISSING+".*");
            if(!missing||!clause.matches("(?s).*"+FACET+".*"))continue;
            if((clause.contains("配方")&&acquired.contains("配方"))
                    ||(clause.contains("交易")&&acquired.contains("交易"))
                    ||(clause.matches("(?s).*(掉落|战利品|宝箱|池|权重).*")&&acquired.contains("战利品表")))
                log.info("answer caveat conflicts with acquired structured record: {}", clause.strip());
        }
    }

    private static String channelNote(String answer,String question,QaPipeline.QaResult result){
        if(question==null||!question.matches("(?s).*(获取|获得|来源|掉落|怎么拿|怎么得).*"))return "";
        if(answer.contains("本轮另核对"))return "";
        var missing=new ArrayList<String>();
        if(!result.lootIds.isEmpty()&&!answer.matches("(?s).*(战利品|宝箱).*"))missing.add("战利品表（已读取 "+result.lootIds.size()+" 张）");
        if(!result.tradeIds.isEmpty()&&!answer.matches("(?s).*交易.*"))missing.add("交易（已读取 "+result.tradeIds.size()+" 条）");
        if(missing.isEmpty())return "";
        return "本轮另核对的渠道："+String.join("、",missing)+"；正文未单列，按本轮记录未命中目标条目处理。";
    }

    /** 渠道只出现在被过滤的否定句里时，补一行“已核对渠道”，只声明核对动作，不声明命中。 */
    private static String channelCoverage(String answer,String question){
        if(question==null||!question.matches("(?s).*(获取|获得|来源|途径).*"))return "";
        String positive=TaskEvidence.positiveText(answer);
        var checked=new ArrayList<String>();
        for(String channel:CHANNELS)
            if(answer.contains(channel)&&!positive.contains(channel))checked.add(channel);
        if(checked.isEmpty())return "";
        return "已核对渠道："+String.join("、",checked)+"（各渠道命中情况见正文）。";
    }
}
