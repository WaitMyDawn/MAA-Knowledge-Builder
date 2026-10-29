package yagen.waitmydawn.kb.service;

import java.util.*;
import java.util.regex.*;

/** 配图请求按对象分支与状态列表拆分；共享条件仅在同一对象中继承，不制造状态笛卡尔积。 */
final class MediaRequestPlanner {
    static final int LIMIT=8;
    record Subject(String registry,String kind,List<String> names) {}
    record Request(Subject subject,String kind,String query,String label) {}
    record Plan(List<Request> requests,boolean comparison,boolean truncated,List<String> warnings) {}
    private record Mention(Subject subject,int start,int end) {}
    private record Value(String axis,String query) {}
    private static final Pattern ID=Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern JOIN=Pattern.compile("以及|还有|和|与|、|，|,|；|;|对比|相比| versus | and | vs\\.? ",Pattern.CASE_INSENSITIVE);
    private static final Pattern CONDITION=Pattern.compile("[a-z_][a-z0-9_]*=[a-z0-9_:/.-]+|第?[一二三四五六七八九十百0-9]+(?:阶段|阶)|浅蓝色|淡蓝色|浅灰色|淡灰色|黄绿色|品红色|宝石蓝|蓝宝石|青铜色|紫晶色|电蓝色|红色|绿色|蓝色|白色|黑色|黄色|紫色|棕色|橙色|粉色|灰色|青色|雌性|雄性|母龙|公龙|成年|幼年|幼体|宝宝|adult|baby|未驯服|驯服|未剪毛|剪毛|未充能|充能|闪电苦力怕|牧师|农民|图书管理员|渔夫|盔甲匠|屠夫|制图师|牧羊人|武器匠|工具匠|皮匠|制箭师|石匠|沙漠|丛林|平原|热带草原|雪地|沼泽|针叶林|虎斑|狸花|暹罗|布偶|波斯|英国短毛|三花|白猫|纯黑|红蓝|黄蓝|傻子|失业|大师|[1-5]级|带电|默认实例|初始化样本|生成初始化|安全准备样本|实际实体捕获|实际捕获|无装备|未装备|裸装|空手",Pattern.CASE_INSENSITIVE);
    static Plan plan(String question,List<Subject> catalog,Set<String> resolved){
        String q=question.replace('`',' ');var mentions=new ArrayList<Mention>();var warnings=new ArrayList<String>();var byId=new HashMap<String,Subject>();catalog.forEach(s->byId.put(s.registry(),s));
        var ids=ID.matcher(q);while(ids.find()){if(ids.start()>0&&q.charAt(ids.start()-1)=='=')continue;mentions.add(new Mention(byId.getOrDefault(ids.group(),new Subject(ids.group(),"UNKNOWN",List.of(ids.group()))),ids.start(),ids.end()));}
        var aliases=new HashMap<String,List<Subject>>();for(var subject:catalog)for(String name:subject.names())if(!name.isBlank()&&!name.equals(subject.registry())&&!Set.of("物品","实体","生物","Item","Entity").contains(name))aliases.computeIfAbsent(name,k->new ArrayList<>()).add(subject);
        var names=new ArrayList<>(aliases.keySet());names.sort(Comparator.comparingInt(String::length).reversed().thenComparing(s->s));
        for(String name:names){int start=0;while((start=q.toLowerCase(Locale.ROOT).indexOf(name.toLowerCase(Locale.ROOT),start))>=0){int from=start,to=start+name.length();start=to;
            if(mentions.stream().anyMatch(m->from<m.end()&&to>m.start()))continue;
            if(name.matches("[a-zA-Z ]+")&&((from>0&&String.valueOf(q.charAt(from-1)).matches("[a-zA-Z0-9_]"))||(to<q.length()&&String.valueOf(q.charAt(to)).matches("[a-zA-Z0-9_]"))))continue;
            var candidates=aliases.get(name).stream().distinct().toList();if(candidates.size()>1){var preferred=candidates.stream().filter(s->resolved.contains(s.registry())).toList();if(preferred.size()==1)candidates=preferred;else{warnings.add("名称“"+name+"”对应多个对象，请指定注册名。");continue;}}
            var candidate=candidates.getFirst();if(name.length()==1&&!resolved.isEmpty()&&!resolved.contains(candidate.registry()))continue;mentions.add(new Mention(candidate,from,to));
        }}
        if(mentions.isEmpty()&&(q.matches("(?is).*(它|这个|上述|刚才|展示|外观|长什么样|比较|对比|图片|分别|show|look).*"))&&resolved.size()==1){var subject=byId.get(resolved.iterator().next());if(subject!=null)mentions.add(new Mention(subject,0,0));}
        mentions.removeIf(m->!m.subject().kind().equals("ENTITY")&&q.substring(Math.max(0,m.start()-12),m.start()).matches("(?s).*(穿着|穿戴|戴着|手持|装备了|装备着)\\s*$"));
        mentions.sort(Comparator.comparingInt(Mention::start));var result=new LinkedHashMap<String,Request>();boolean comparison=q.matches("(?is).*(比较|对比|区别|分别|和|与|、| versus | vs ).*");
        var cuts=new ArrayList<Integer>();cuts.add(0);for(int i=0;i+1<mentions.size();i++){var matcher=JOIN.matcher(q);int cut=-1;while(matcher.find())if(matcher.start()>=mentions.get(i).end()&&matcher.end()<=mentions.get(i+1).start())cut=matcher.end();cuts.add(cut<0?mentions.get(i+1).start():cut);}cuts.add(q.length());
        String shared="";if(mentions.size()>1){boolean earlierState=false;for(int i=0;i+1<mentions.size();i++)if(CONDITION.matcher(q.substring(mentions.get(i).end(),mentions.get(i+1).start())).find())earlierState=true;
            String suffix=q.substring(mentions.getLast().end());if(!earlierState&&suffix.stripLeading().startsWith("的"))shared=suffix;
        }
        for(int i=0;i<mentions.size();i++){
            var mention=mentions.get(i);String branch=q.substring(cuts.get(i),cuts.get(i+1));if(!shared.isBlank()&&i+1<mentions.size())branch+=" "+shared;
            String stateBranch=branch;for(String name:mention.subject().names().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList())stateBranch=stateBranch.replaceAll("(?i)"+Pattern.quote(name)," ");stateBranch=stateBranch.replace(mention.subject().registry()," ");
            var pieces=new ArrayList<Map<String,Value>>();for(String piece:JOIN.split(stateBranch)){var conditions=new LinkedHashMap<String,Value>();var matcher=CONDITION.matcher(piece.toLowerCase(Locale.ROOT));while(matcher.find()){Value value=value(matcher.group());if(conditions.containsKey(value.axis())&&!conditions.get(value.axis()).query().equals(value.query())){warnings.add("同一分支存在冲突状态，请用“与”或“分别”拆开："+piece);conditions.put("conflict",new Value("conflict","unsupported_conflict=true"));}else conditions.put(value.axis(),value);}if(!conditions.isEmpty())pieces.add(conditions);}
            if(pieces.isEmpty())pieces.add(new LinkedHashMap<>());
            var common=new LinkedHashMap<String,Value>();var distinct=new HashMap<String,Set<Value>>();pieces.forEach(p->p.forEach((k,v)->distinct.computeIfAbsent(k,x->new LinkedHashSet<>()).add(v)));distinct.forEach((k,v)->{if(v.size()==1)common.put(k,v.iterator().next());});
            String kind=kind(stateBranch,q,mention.subject().kind());String extra=equipment(branch);
            // 保留未规范化的上下文，供快照声明的自定义轴标签继续匹配。
            String residual=CONDITION.matcher(stateBranch).replaceAll(" ");
            for(var piece:pieces){var merged=new TreeMap<>(common);merged.putAll(piece);String selectors=merged.values().stream().map(Value::query).collect(java.util.stream.Collectors.joining(" "));String query=mention.subject().registry()+" "+selectors+" "+extra+" "+residual+(kind.equals("ENTITY")?" 实体":" 物品");String label=mention.subject().names().stream().filter(n->!n.contains(":")).findFirst().orElse(mention.subject().registry())+(selectors.isBlank()?"":" · "+selectors);var request=new Request(mention.subject(),kind,query,label);result.putIfAbsent(mention.subject().registry()+"|"+kind+"|"+selectors+"|"+extra,request);}
        }
        boolean truncated=result.size()>LIMIT;if(truncated)warnings.add("配图请求超过"+LIMIT+"项，只处理前"+LIMIT+"项；可分开提问。");return new Plan(result.values().stream().limit(LIMIT).toList(),comparison,truncated,List.copyOf(new LinkedHashSet<>(warnings)));
    }
    private static String kind(String branch,String full,String registered){boolean entity=branch.matches("(?is).*(实体|生物|怪物|entity).*"),item=branch.matches("(?is).*(物品|道具|item).*" );if(entity&&!item)return "ENTITY";if(item&&!entity)return "ITEM";if(full.contains("实体")&&!full.contains("物品"))return "ENTITY";return registered.equals("ENTITY")?"ENTITY":"ITEM";}
    private static String equipment(String branch){var m=Pattern.compile("(?:穿着|穿戴|戴着|手持|装备了|装备着)([^，。；和与]{1,70})").matcher(branch);return m.find()?m.group():"";}
    private static Value value(String word){if(word.contains("="))return new Value(word.substring(0,word.indexOf('=')),word);
        if(word.matches("第?[一二三四五六七八九十百0-9]+(?:阶段|阶)")){String v=word.replaceAll("第|阶段|阶","");String[] n={"一","二","三","四","五","六","七","八","九","十"};for(int i=0;i<n.length;i++)if(v.equals(n[i]))v=Integer.toString(i+1);return new Value("growth_stage","growth_stage="+v);}
        return switch(word){case "成年","adult"->new Value("age","age=adult");case "幼年","幼体","宝宝","baby"->new Value("age","age=baby");case "雌性","母龙"->new Value("sex","sex=female");case "雄性","公龙"->new Value("sex","sex=male");case "驯服"->new Value("tamed","tamed=true");case "未驯服"->new Value("tamed","tamed=false");case "剪毛"->new Value("sheared","sheared=true");case "未剪毛"->new Value("sheared","sheared=false");case "带电","闪电苦力怕","充能"->new Value("charged","charged=true");case "未充能"->new Value("charged","charged=false");case "宝石蓝","蓝宝石"->new Value("color","variant=sapphire");default->{String axis=word.matches(".*色")?"color":word.matches(".*(实例|样本|捕获|初始化)")?"origin":word.matches(".*(装备|裸装|空手)")?"equipment":Set.of("沙漠","丛林","平原","热带草原","雪地","沼泽","针叶林").contains(word)?"biome":Set.of("虎斑","狸花","暹罗","布偶","波斯","英国短毛","三花","白猫","纯黑","红蓝","黄蓝").contains(word)?"variant":word.equals("大师")||word.matches("[1-5]级")?"level":"profession";yield new Value(axis,word);}};
    }
}
