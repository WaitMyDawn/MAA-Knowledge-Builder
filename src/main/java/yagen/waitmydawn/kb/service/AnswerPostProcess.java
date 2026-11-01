package yagen.waitmydawn.kb.service;

import java.util.*;

/**
 * 回答的确定性整理：只删除“没有信息量”的内容，不改写事实。
 *
 * <p>当前处理两类：与问题无关的“没有装备槽位”式说明；整列为空（—、-、无、空）的表格列。
 * 其余排版问题交给生成规则和人工评审，避免误删玩家需要的信息。
 */
final class AnswerPostProcess {
    private AnswerPostProcess(){}

    static String apply(String answer,String question){
        if(answer==null||answer.isBlank())return answer;
        String text=dropEmptyEquipmentRemarks(answer,question);
        text=dropEmptyTableColumns(text);
        return text;
    }

    /** 玩家没问装备时，不写“没有装备槽位”这类无信息句。 */
    static String dropEmptyEquipmentRemarks(String answer,String question){
        if(question!=null&&question.matches("(?s).*(装备|槽位|穿戴|头盔|护甲|手持|武器).*"))return answer;
        var lines=answer.split("\\R",-1);var kept=new ArrayList<String>();
        for(String line:lines){
            if(line.matches(".*(没有|无|不含|未提供|缺少)[^。；\\n]{0,12}装备槽位.*"))continue;
            kept.add(line);
        }
        return String.join("\n",kept);
    }

    /** 整列都是空值的表格列直接删掉，不留空来源列。 */
    static String dropEmptyTableColumns(String answer){
        var lines=new ArrayList<>(Arrays.asList(answer.split("\\R",-1)));
        int index=0;
        while(index<lines.size()){
            if(!isTableRow(lines.get(index))){index++;continue;}
            int start=index;while(index<lines.size()&&isTableRow(lines.get(index)))index++;
            var block=new ArrayList<>(lines.subList(start,index));
            var fixed=dropEmptyColumns(block);
            if(fixed.size()!=block.size()){lines.subList(start,index).clear();lines.addAll(start,fixed);index=start+fixed.size();}
        }
        return String.join("\n",lines);
    }

    private static boolean isTableRow(String line){String trimmed=line.trim();return trimmed.startsWith("|")&&trimmed.endsWith("|")&&trimmed.length()>1;}

    private static List<String> dropEmptyColumns(List<String> rows){
        var cells=rows.stream().map(AnswerPostProcess::splitCells).toList();
        int width=cells.stream().mapToInt(List::size).min().orElse(0);
        if(width<2)return rows;
        var empty=new boolean[width];
        for(int column=0;column<width;column++){
            boolean blank=true;
            for(int row=0;row<rows.size();row++){
                if(row==1)continue; // 分隔行
                String value=cells.get(row).get(column);
                if(!value.isBlank()&&!value.matches("[—-]{1,3}|无|空|N/A|—")){blank=false;break;}
            }
            empty[column]=blank;
        }
        return rows.stream().map(row->{
            var parts=splitCells(row);var kept=new ArrayList<String>();
            for(int column=0;column<parts.size();column++)if(column>=width||!empty[column])kept.add(parts.get(column));
            return "|"+String.join("|",kept)+"|";
        }).toList();
    }

    private static List<String> splitCells(String row){
        String trimmed=row.trim();if(trimmed.startsWith("|"))trimmed=trimmed.substring(1);if(trimmed.endsWith("|"))trimmed=trimmed.substring(0,trimmed.length()-1);
        return Arrays.stream(trimmed.split("\\|",-1)).map(String::trim).toList();
    }
}
