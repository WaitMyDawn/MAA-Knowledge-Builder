package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.*;

/** 图片引用仅指向同一实体的直接资产所有者，不允许跨实体、链式或循环引用。 */
final class EntityImageReferences {
    private EntityImageReferences() {}
    static Map<String,JsonNode> index(JsonNode records)throws IOException {
        var rows=new HashMap<String,JsonNode>();for(var row:records)if(!row.has("error")){String id=row.path("id").asText()+"|"+EntityAppearanceState.key(row);if(rows.put(id,row)!=null)throw new IOException("重复实体状态");}return rows;
    }
    static JsonNode resolve(JsonNode row,Map<String,JsonNode> rows)throws IOException {
        if(!row.has("imageRef"))return row;
        var owner=rows.get(row.path("id").asText()+"|"+row.path("imageRef").asText());
        if(!row.path("imageRef").isTextual()||owner==null||owner==row||owner.has("error")||owner.has("imageRef")||row.has("pngBase64"))throw new IOException("实体图片引用缺失、循环或无效");
        for(String field:List.of("sha256","pixelSha256","width","height","status","file"))if(!row.has(field)||!row.path(field).equals(owner.path(field)))throw new IOException("实体图片引用属性不一致："+field);
        ObjectNode resolved=row.deepCopy();for(String field:List.of("pngBase64","assetPath"))if(owner.has(field))resolved.set(field,owner.path(field));return resolved;
    }
}
