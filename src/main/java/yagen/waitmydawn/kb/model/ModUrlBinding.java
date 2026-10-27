package yagen.waitmydawn.kb.model;

import java.net.URI;
import java.util.*;

/** 百科候选与人工设置不是身份认证；浏览量仅用于审阅排序。 */
public record ModUrlBinding(String url,String chineseName,String englishName,Long views,String status,String origin,String checkedAt,String note) {
    public ModUrlBinding {
        url=url==null||url.isBlank()?null:normalize(url);
        if(views!=null&&views<0)throw new IllegalArgumentException("浏览量不能为负数");
        status=Objects.requireNonNullElse(status,"UNBOUND");origin=Objects.requireNonNullElse(origin,"AUTO");
        if(!Set.of("UNBOUND","FOUND","NOT_FOUND","FAILED","DISABLED","BUILTIN").contains(status))throw new IllegalArgumentException("未知绑定状态");
    }
    public static String normalize(String input){
        URI uri=URI.create(input.strip());String host=Objects.toString(uri.getHost(),"").toLowerCase(Locale.ROOT);
        if(!Set.of("http","https").contains(Objects.toString(uri.getScheme(),"").toLowerCase(Locale.ROOT))||!Set.of("mcmod.cn","www.mcmod.cn").contains(host)||uri.getRawUserInfo()!=null||uri.getPort()!=-1||!uri.getPath().matches("/class/[1-9][0-9]*\\.html"))throw new IllegalArgumentException("请输入 MC百科模组主页：https://www.mcmod.cn/class/数字.html");
        return "https://www.mcmod.cn"+uri.getPath();
    }
    public String classId(){return url==null?null:URI.create(url).getPath().replaceAll("[^0-9]","");}
    public String alias(){String cn=Objects.toString(chineseName,"").strip(),en=Objects.toString(englishName,"").strip();return cn.isEmpty()?en:en.isEmpty()||cn.equalsIgnoreCase(en)?cn:cn+"（"+en+"）";}
    public String signature(){return Objects.toString(url,"")+"|"+status.equals("DISABLED");}
    public String statusLabel(){return switch(status){case "FOUND"->origin.equals("MANUAL")?"人工设置":"自动候选";case "NOT_FOUND"->"未找到";case "FAILED"->"请求失败";case "DISABLED"->"不使用百科";case "BUILTIN"->"原版知识源";default->"未绑定";};}
    public static boolean vanilla(ModEntry mod){return "minecraft".equals(mod.getModId())||"vanilla".equals(mod.getLoader());}
    public static ModUrlBinding vanilla(){return new ModUrlBinding(null,"我的世界","Minecraft",null,"BUILTIN","BUILTIN",null,null);}
    public static Comparator<ModEntry> reviewOrder(){return Comparator.comparingInt((ModEntry m)->vanilla(m)?0:m.getMcmodBinding()!=null&&m.getMcmodBinding().url()!=null?(m.getMcmodBinding().views()==null?2:1):3)
            .thenComparing(m->m.getMcmodBinding()==null||m.getMcmodBinding().views()==null?0L:m.getMcmodBinding().views(),Comparator.reverseOrder())
            .thenComparing(m->Objects.toString(m.getModId(),""))
            .thenComparing(m->Objects.toString(m.getJarPath(),""));}
}
