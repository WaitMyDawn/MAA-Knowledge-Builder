package yagen.waitmydawn.kb.service;

import org.jsoup.nodes.Document;
import yagen.waitmydawn.kb.model.*;
import java.time.Instant;
import java.util.*;

/** 搜索只产生候选，不用浏览量或名称相似度声明身份已验证。 */
public final class ModUrlBinder {
    private final WikiScraperService scraper;
    public ModUrlBinder(WikiScraperService scraper){this.scraper=scraper;}
    public ModUrlBinding bind(ModEntry mod){
        if(ModUrlBinding.vanilla(mod))return ModUrlBinding.vanilla();
        var previous=mod.getMcmodBinding();
        if(previous!=null&&Set.of("MANUAL","SEED").contains(previous.origin()))return previous;
        try {
            String url=scraper.findModCandidateUrl(mod.getModId());
            String displayName=mod.getDisplayName();
            if(url==null&&displayName!=null&&!displayName.isBlank()&&!displayName.strip().equalsIgnoreCase(Objects.toString(mod.getModId(),"").strip()))
                url=scraper.findModCandidateUrl(displayName.strip());
            return url==null?new ModUrlBinding(null,null,null,null,"NOT_FOUND","AUTO",Instant.now().toString(),null):inspect(url,"AUTO");
        }catch(java.util.concurrent.CancellationException e){throw e;}
        catch(Exception e){return new ModUrlBinding(previous==null?null:previous.url(),previous==null?null:previous.chineseName(),previous==null?null:previous.englishName(),previous==null?null:previous.views(),"FAILED","AUTO",Instant.now().toString(),"搜索请求失败，稍后重试");}
    }
    public ModUrlBinding inspect(String input,String origin){
        String url=ModUrlBinding.normalize(input);
        try {
            var resource=scraper.fetchBuildResource(url,"https://www.mcmod.cn/",false);
            if(resource.status()!=200||resource.bytes()==null)throw new IllegalStateException("HTTP "+resource.status());
            if(resource.bytes().length>4*1024*1024)throw new IllegalStateException("页面过大");
            String finalUrl=ModUrlBinding.normalize(resource.url());
            return parse(resource.document(),finalUrl,origin);
        }catch(java.util.concurrent.CancellationException e){throw e;}
        catch(Exception e){return new ModUrlBinding(url,null,null,null,"FAILED",origin,Instant.now().toString(),"主页信息获取失败");}
    }
    static ModUrlBinding parse(Document document,String url,String origin){
        var title=document.selectFirst(".class-title");
        if(title==null||title.selectFirst("h3")==null)throw new IllegalArgumentException("不是可识别的模组主页");
        String cn=title.selectFirst("h3").text().strip(),en=title.selectFirst("h4")==null?null:title.selectFirst("h4").text().strip();Long views=null;
        for(var block:document.select(".infos .span")) {
            String label=block.text()+" "+block.attr("aria-label");
            for(var child:block.select("[title]"))label+=" "+child.attr("title");
            if(!label.contains("总浏览"))continue;
            String value=block.attr("title").replace(",","").strip();
            if(value.matches("[0-9]{1,19}"))try{views=Long.parseLong(value);}catch(NumberFormatException ignored){}
            break;
        }
        return new ModUrlBinding(url,cn,en,views,"FOUND",origin,Instant.now().toString(),views==null?"浏览量未知":null);
    }
}
