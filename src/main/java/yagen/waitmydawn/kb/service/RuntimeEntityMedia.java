package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.Path;
import java.util.*;
import java.io.*;

/** 实体插图只按当前快照的精确实体注册名关联；历史回答保存不可变图片身份。 */
final class RuntimeEntityMedia {
    private final DatabaseBuilder db;private final Path directory;
    RuntimeEntityMedia(DatabaseBuilder db,Path directory){this.db=db;this.directory=directory;}
    static byte[] bytes(JsonNode row)throws IOException {
        try{byte[] bytes=Base64.getDecoder().decode(row.path("pngBase64").asText());if(bytes.length>2*1024*1024)throw new IOException("实体图像超过预算");return bytes;}catch(IllegalArgumentException e){throw new IOException("实体 PNG 编码无效",e);}
    }
    static byte[] externalBytes(Path root,JsonNode row)throws IOException {
        if(root==null||!row.path("file").isTextual())throw new IOException("实体独立 PNG 缺少目录或路径");
        Path file=ArchivePaths.resolve(root,row.path("file").asText());
        if(!java.nio.file.Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)||java.nio.file.Files.size(file)>2L*1024*1024)throw new IOException("独立实体 PNG 缺失或超过预算");
        try(var input=java.nio.file.Files.newInputStream(file)){byte[] bytes=input.readNBytes(2*1024*1024+1);if(bytes.length>2*1024*1024)throw new IOException("实体 PNG 超过预算");return bytes;}
    }
    private byte[] storedBytes(JsonNode row)throws IOException {
        if(!row.has("assetPath"))return bytes(row);
        String hash=row.path("sha256").asText();Path file=new ImageAssetStore(directory).resolve(hash,row.path("assetPath").asText());
        var copy=((com.fasterxml.jackson.databind.node.ObjectNode)row).deepCopy();copy.put("file",directory.toAbsolutePath().normalize().relativize(file).toString().replace('\\','/'));
        byte[] png=externalBytes(directory,copy);validate(row,png);return png;
    }
    static void validate(JsonNode row)throws IOException {validate(row,bytes(row));}
    static void validate(JsonNode row,byte[] bytes)throws IOException {
        RecipePlan.resourceId(row.path("id").asText());EntityCompositionInfo.validate(row);
        if(!row.has("imageRef")&&row.has("file")&&!row.path("file").asText().equals(EntityAppearanceState.path(row))&&!row.path("file").asText().equals(EntityAppearanceState.readablePath(row))&&!EntityAppearanceState.flatCandidate(row))throw new IOException("实体 PNG 路径与注册名不一致");
        if(!Set.of("DEFAULT_INSTANCE","SPAWN_INITIALIZED","OBSERVED_INSTANCE","DECLARED_STATE").contains(row.path("appearance").asText())||!Set.of("OK","TRANSPARENT").contains(row.path("status").asText())||!row.path("width").isIntegralNumber()||!row.path("height").isIntegralNumber()||!Set.of(128,256,512).contains(row.path("width").asInt())||!row.path("width").equals(row.path("height")))throw new IOException("实体外观或尺寸无效");
        EntityAppearanceState.key(row);if(row.has("state")&&!row.path("appearance").asText().equals(row.path("state").path("origin").asText()))throw new IOException("外观来源不一致");
        try{String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));if(!hash.equals(row.path("sha256").asText()))throw new IOException("实体 PNG 哈希不匹配");}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        if(bytes.length<8||bytes[0]!=(byte)137||bytes[1]!=80||bytes[2]!=78||bytes[3]!=71)throw new IOException("实体图像不是 PNG");
        try(var input=javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("实体 PNG 无法解码");var reader=readers.next();
            try {reader.setInput(input);if(reader.getWidth(0)!=row.path("width").asInt()||reader.getHeight(0)!=row.path("height").asInt())throw new IOException("实体 PNG 尺寸无效");var image=reader.read(0);if(!image.getColorModel().hasAlpha())throw new IOException("实体 PNG 缺少透明通道");
                int left=image.getWidth(),top=image.getHeight(),right=-1,bottom=-1;
                for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)>>>24)!=0){left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);}
                if(row.path("status").asText().equals("TRANSPARENT")!=(right<0))throw new IOException("实体 PNG 透明状态与像素不一致");
                if(row.has("framingBounds")) {
                    var box=row.path("framingBounds");int[] actual={left,top,right+1,bottom+1};if(!box.isArray()||box.size()!=4||right<0)throw new IOException("实体取景边界无效");
                    for(int i=0;i<4;i++)if(!box.get(i).isNumber()||box.get(i).asDouble()!=actual[i])throw new IOException("实体取景边界与 PNG 不一致");
                    int size=image.getWidth();double occupancy=(double)Math.max(right+1-left,bottom+1-top)/size;double dx=(left+right+1)*.5/size-.5,dy=(top+bottom+1)*.5/size-.5;
                    if(!row.path("framingOccupancy").isNumber()||Math.abs(row.path("framingOccupancy").asDouble()-occupancy)>1e-6||!row.path("framingOffsetX").isNumber()||!row.path("framingOffsetY").isNumber()||Math.abs(row.path("framingOffsetX").asDouble()-dx)>1e-6||Math.abs(row.path("framingOffsetY").asDouble()-dy)>1e-6)throw new IOException("实体取景指标与 PNG 不一致");
                    if(row.path("framing").asText().equals("FITTED")&&(occupancy<.76||occupancy>.84||Math.abs(dx)>.02||Math.abs(dy)>.02||left<2||top<2||right+1>size-2||bottom+1>size-2))throw new IOException("实体图片未通过最终取景校验");
                }
            }finally{reader.dispose();}
        }
    }
    static void validateExternal(Path root,JsonNode row)throws IOException {
        if(row.has("error")||!row.has("file"))return;
        Path file=ArchivePaths.resolve(root,row.path("file").asText());
        if(!java.nio.file.Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)||java.nio.file.Files.size(file)>2*1024*1024)throw new IOException("独立实体 PNG 缺失或超过预算");
        if(!Arrays.equals(java.nio.file.Files.readAllBytes(file),bytes(row)))throw new IOException("独立实体 PNG 与快照图像不一致");
    }
    void attach(QaPipeline.QaResult result,String question) {
        String profile=RuntimeKnowledgeStore.active(db);var trail=new MediaAudit.Trail(profile,question);result.entityImageAudit=trail.snapshot();if(profile==null)return;
        var ids=new LinkedHashSet<String>();result.resolvedEntities.stream().filter(e->e.confidence()>=0.8f).forEach(e->ids.add(e.registry()));
        var imageRows=new HashMap<String,JsonNode>();
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT record_id,record_data FROM rag_runtime_record WHERE profile_id=? AND category='entity_icons'")){p.setString(1,profile);try(var r=p.executeQuery()){while(r.next())imageRows.put(r.getString(1),new ObjectMapper().readTree(r.getString(2)));}}catch(Exception failure){throw new IllegalStateException("实体图片引用读取失败",failure);}
        var candidates=new TreeMap<String,List<JsonNode>>();var requested=new HashSet<String>();
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT record_id,record_data FROM rag_runtime_record WHERE profile_id=? AND category='entity_icons' ORDER BY record_id")) {
            var equipment=new HashSet<String>();
            if(question.matches("(?s).*(装备|穿戴|穿着|戴着|持有|手持|头盔|护甲).*")) {
                var idsInQuestion=java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+").matcher(question);while(idsInQuestion.find())equipment.add(idsInQuestion.group());
                String text=question.replaceAll("[a-z0-9_.-]+:[a-z0-9_./-]+"," ").toLowerCase(Locale.ROOT);
                record NameHit(int start,int end,String id) {}var hits=new ArrayList<NameHit>();
                try(var names=c.prepareStatement("SELECT item_id,display_name FROM rag_runtime_name WHERE profile_id=?")){names.setString(1,profile);try(var rows=names.executeQuery()){while(rows.next()){String name=rows.getString(2).toLowerCase(Locale.ROOT);if(name.length()<2)continue;for(int start=text.indexOf(name);start>=0;start=text.indexOf(name,start+1))hits.add(new NameHit(start,start+name.length(),rows.getString(1)));}}}
                hits.sort(Comparator.comparingInt((NameHit hit)->hit.end()-hit.start()).reversed());var occupied=new BitSet(text.length());
                for(var hit:hits){int overlap=occupied.nextSetBit(hit.start());if(overlap>=0&&overlap<hit.end())continue;occupied.set(hit.start(),hit.end());equipment.add(hit.id());}
            }
            p.setString(1,profile);try(var r=p.executeQuery()){while(r.next()) {
                trail.scanned++;String id=r.getString(1).split("\\|",2)[0];
                boolean explicit=java.util.regex.Pattern.compile("(?<![a-z0-9_./:-])"+java.util.regex.Pattern.quote(id)+"(?![a-z0-9_./:-])").matcher(question).find();
                if(!ids.contains(id)&&!explicit)continue;requested.add(id);
                var row=new ObjectMapper().readTree(r.getString(2));String exclusion=row.has("error")?"EXPORT_ERROR":!row.path("status").asText().equals("OK")?"NOT_VISIBLE":!EntityAppearanceState.matches(row,question)?"STATE_MISMATCH":!EntityAppearanceState.equipmentMatches(row,equipment)?"EQUIPMENT_MISMATCH":null;
                if(exclusion!=null){trail.record(row,"EXCLUDED",exclusion);continue;}
                boolean explicitEntity=java.util.regex.Pattern.compile("实体|\\bentity\\b",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(question).find();
                if(!explicitEntity) {
                    if(row.path("classification").asText().equals("NON_LIVING")){trail.record(row,"EXCLUDED","NON_LIVING_REQUIRES_ENTITY_REQUEST");continue;}
                    try(var item=c.prepareStatement("SELECT 1 FROM rag_runtime_name WHERE profile_id=? AND item_id=? LIMIT 1")){item.setString(1,profile);item.setString(2,id);try(var names=item.executeQuery()){if(names.next()){trail.record(row,"EXCLUDED","ITEM_ID_COLLISION_REQUIRES_ENTITY_REQUEST");continue;}}}
                }
                trail.record(row,"ELIGIBLE","REGISTRY_AND_STATE_MATCH");candidates.computeIfAbsent(id,ignored->new ArrayList<>()).add(row);
            }}
            var explicitIds=java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+").matcher(question);var missing=new LinkedHashSet<>(ids);while(explicitIds.find())missing.add(explicitIds.group());missing.removeAll(requested);
            for(String id:missing){String reason="NO_EXPORTED_ENTITY_RECORD";try(var lookup=c.prepareStatement("SELECT record_data FROM rag_runtime_record WHERE profile_id=? AND category='entity_exclusions' AND record_id=?")){lookup.setString(1,profile);lookup.setString(2,id);try(var rows=lookup.executeQuery()){if(rows.next())reason="EXPORT_EXCLUDED_"+new ObjectMapper().readTree(rows.getString(1)).path("reason").asText();}}
                var absent=new ObjectMapper().createObjectNode().put("id",id).put("stateKey","default");trail.record(absent,"EXCLUDED",reason);
            }
            var pictures=new ArrayList<>(result.artifacts.pictures());int added=0;
            for(var entry:candidates.entrySet()) {
                if(added>=2){entry.getValue().forEach(row->trail.record(row,"EXCLUDED","GLOBAL_IMAGE_LIMIT"));continue;}
                var rows=entry.getValue();rows.sort(Comparator.comparingInt(EntityAppearanceState::preference).thenComparing(row->row.path("stateKey").asText("default")));
                var row=rows.getFirst();for(int index=1;index<rows.size();index++)trail.record(rows.get(index),"EXCLUDED","LOWER_STATE_PREFERENCE");
                String id=entry.getKey(),state=EntityAppearanceState.key(row),key="e"+BuildRequest.fingerprint(profile+id+state).substring(0,20);
                if(pictures.stream().anyMatch(picture->picture.id().equals(key))){trail.record(row,"EXCLUDED","ALREADY_ATTACHED");continue;}
                var asset=new ImageAssetStore(directory).put(storedBytes(EntityImageReferences.resolve(row,imageRows)));String label=EntityAppearanceState.label(row)+EntityCompositionInfo.caption(row);if(row.path("framing").asText().equals("POSSIBLY_CLIPPED"))label+="；取景可能不完整";else if(row.path("framing").asText().equals("UNSTABLE_FRAMING"))label+="；取景未收敛，待复核";
                pictures.add(new AnswerArtifacts.Picture(key,asset.id(),asset.relativePath(),id+"（"+label+"）",null,"ENTITY",List.of(),"状态键："+state+"；"+label));
                result.answer+="\n\n![实体外观](kb-image:"+key+")";added++;trail.selected++;trail.record(row,"SELECTED","REGISTRY_AND_STATE_MATCH");
            }
            if(added==0&&EntityAppearanceState.specific(question)&&!requested.isEmpty())result.answer+="\n\n当前快照未提供与所问状态匹配的实体插图，未用默认外观代替。";
            result.artifacts=new AnswerArtifacts(result.artifacts.scopeId(),result.artifacts.scopeLabel(),result.artifacts.sources(),pictures,result.artifacts.citedSourceIds());trail.requested=requested.size();result.entityImageAudit=trail.snapshot();
        }catch(Exception failure){throw new IllegalStateException("实体插图读取失败",failure);}
    }
}
