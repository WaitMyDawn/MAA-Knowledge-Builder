package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.util.*;

/** 已选快照的事实边界与模型补答约束；网页版本未验证时不推断兼容性。 */
public final class RuntimeEvidencePolicy {
    public record Snapshot(String profile,String minecraftVersion,String loader,Map<String,String> mods){}
    private RuntimeEvidencePolicy(){}
    public static Snapshot snapshot(DatabaseBuilder db) {
        String active=RuntimeKnowledgeStore.active(db);if(active==null)return null;
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT i.manifest_json FROM rag_runtime_snapshot s JOIN rag_item_icon_profile i ON i.id=s.profile_id WHERE s.profile_id=?")) {
            p.setString(1,active);try(var rows=p.executeQuery()) {
                if(!rows.next())throw new IllegalStateException("当前知识快照缺少环境清单");
                var env=new ObjectMapper().readTree(rows.getString(1)).path("environment");var mods=new TreeMap<String,String>();
                for(var mod:env.path("mods"))mods.put(mod.path("id").asText(),mod.path("version").asText("unknown"));
                return new Snapshot(active,env.path("minecraftVersion").asText("unknown"),env.path("loader").asText("unknown"),Map.copyOf(mods));
            }
        }catch(java.sql.SQLException|java.io.IOException failure){throw new IllegalStateException("游戏快照证据边界读取失败",failure);}
    }
    public static String context(Snapshot snapshot) {
        return context(snapshot,false);
    }
    public static String context(Snapshot snapshot,boolean documentScope) {
        if(snapshot==null)return "";
        return "\n[Selected runtime snapshot — authoritative scope]\nprofile="+snapshot.profile()+"; Minecraft Java Edition="+snapshot.minecraftVersion()+"; loader="+snapshot.loader()+"; installedMods="+new TreeMap<>(snapshot.mods())
                +"\n当前配方、标签、名称、注册名和外观状态仅以此快照为准。网页默认配方不得覆盖快照。未命中不表示无法合成或状态不存在。"
                +(documentScope?"\n[Document evidence scope] 本轮按所选文档或整合包教程介绍其自身内容。当前快照只标识本地资源环境，不据此否定外部资料，也不把文档配方视为当前世界已经验证的配方。未确认当前世界的安装对应关系与版本兼容性时明确说明。":"\n所有原版与模组的机制、物品、战术和数量都必须由本轮证据支持并适用于当前版本。")
                +"\n不得用模型记忆填补空缺。网页未声明版本不等于已确认兼容。"
                +"\n仅当用户明确进行跨版本对比时，才分别标明证据适用版本，不把其他版本的事实用于当前环境。导出外观是元数据，不是对图片像素的视觉判断，也不证明属性、掉落或真实体型。"
                +"\n网页讨论的未安装模组仍可作为外部资料回答，必须说明它不在当前快照中。缺少证据的部分说明本轮未确认，不声称整个资料都没有记载。\n";
    }
}
