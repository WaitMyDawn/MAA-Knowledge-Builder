package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.*;
import java.util.*;

/** 模组身份和实体注册表来自快照；Scan 仅提供路径、slug 与可校对的百科绑定。 */
final class RuntimeGameIdentity {
    static List<ModEntry> entries(RuntimeBuildContract.Prepared prepared)throws Exception {
        var scanned=new HashMap<String,ModEntry>();prepared.identities().forEach(i->scanned.put(i.mod().getModId(),i.mod()));
        var result=new ArrayList<ModEntry>();var pack=prepared.pack();
        for(var row:RuntimeSnapshotData.read(pack).categories().get("mod_metadata").path("records")) {
            String id=row.path("id").asText();var mod=new ModEntry();mod.setModId(id);mod.setVersion(row.path("version").asText());
            mod.setDisplayName(row.path("displayName").asText());mod.setDescription(row.path("description").asText());mod.setMcVersion(pack.minecraftVersion());
            mod.setLoader(id.equals("minecraft")?"vanilla":pack.loader());mod.setSource("mkb-export");
            var scan=scanned.get(id);if(scan!=null){mod.setJarPath(scan.getJarPath());mod.setJarFileName(scan.getJarFileName());mod.setSlug(scan.getSlug());mod.setModrinthUrl(scan.getModrinthUrl());mod.setMcmodBinding(scan.getMcmodBinding());}
            result.add(mod);
        }
        return result;
    }
    static void entities(DatabaseBuilder db,NativeIconPack.Pack pack)throws Exception {
        var store=new SourceLifecycleStore(db);var ids=new HashSet<String>();
        String profile=pack.manifestHash();
        try(var c=db.getConnection();var p=c.prepareStatement("MERGE INTO rag_entity_registry (registry_name,mod_id,source_path) KEY(registry_name) VALUES(?,?,?)")) {
            for(var row:RuntimeSnapshotData.read(pack).categories().get("registries").path("records"))if(row.path("registry").asText().equals("entity_type")) {
                String id=row.path("id").asText();ids.add(id);p.setString(1,id);p.setString(2,id.substring(0,id.indexOf(':')));p.setString(3,"mkb-runtime:"+profile+"/registries/"+id);p.addBatch();
            }p.executeBatch();
        }
        for(String old:store.strings("SELECT registry_name FROM rag_entity_registry"))if(!ids.contains(old))store.execute("DELETE FROM rag_entity_registry WHERE registry_name=?",old);
    }
}
