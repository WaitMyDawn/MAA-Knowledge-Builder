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
    private String catalogProfile;
    private List<MediaRequestPlanner.Subject> catalog=List.of();
    void prepare(QaPipeline.QaResult result,String question){
        if(result.runtimeMediaPrepared)return;result.runtimeMediaPrepared=true;
        String profile=RuntimeKnowledgeStore.active(db);var trail=new MediaAudit.Trail(profile,question);result.entityImageAudit=trail.snapshot();if(profile==null)return;
        if(!profile.equals(catalogProfile)){catalog=MediaCatalog.load(db,profile);catalogProfile=profile;}
        var resolved=new LinkedHashSet<String>();result.resolvedEntities.stream().filter(e->e.confidence()>=.8f).forEach(e->resolved.add(e.registry()));
        var imageRows=new LinkedHashMap<String,JsonNode>();var byId=new HashMap<String,List<JsonNode>>();
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT record_id,record_data FROM rag_runtime_record WHERE profile_id=? AND category='entity_icons' ORDER BY record_id")){
            p.setString(1,profile);try(var rows=p.executeQuery()){while(rows.next()){var row=new ObjectMapper().readTree(rows.getString(2));imageRows.put(rows.getString(1),row);byId.computeIfAbsent(row.path("id").asText(),k->new ArrayList<>()).add(row);}}
        }catch(Exception failure){throw new IllegalStateException("实体图片引用读取失败",failure);}
        var subjects=new ArrayList<>(catalog);var known=subjects.stream().map(MediaRequestPlanner.Subject::registry).collect(java.util.stream.Collectors.toSet());for(String id:byId.keySet())if(!known.contains(id))subjects.add(new MediaRequestPlanner.Subject(id,"ENTITY",List.of(id)));
        var plan=MediaRequestPlanner.plan(question,subjects,resolved);result.mediaPlan=plan;result.mediaWarnings.addAll(plan.warnings());
        var pictures=new ArrayList<>(result.artifacts.pictures());var sources=new ArrayList<>(result.artifacts.sources());ItemIconService.Snapshot icons=null;
        for(var request:plan.requests()){
            String id=request.subject().registry();trail.request=request.query();trail.requested++;
            if(pictures.size()>=8){missing(result,request,"已达到本轮8张配图上限");continue;}
            try{
                if(request.kind().equals("ITEM")){
                    if(pictures.stream().anyMatch(p->p.role().equals("RECIPE")&&p.context().contains(id)))continue;
                    if(EntityAppearanceState.specific(request.query())||request.query().matches("(?s).*[a-z_]+=[^ ]+.*")){missing(result,request,"未提供该物品状态的精确图标，未用默认图代替");continue;}
                    if(icons==null)icons=new ItemIconService(db,directory).snapshot();var icon=icons.item(id,false);
                    if(icon.source().equals("NATIVE_TRANSPARENT")){missing(result,request,"默认物品图为全透明");continue;}
                    if(!icon.source().equals("NATIVE")||icon.assetId()==null){missing(result,request,"当前快照没有可用的实机物品图");continue;}
                    String relative;try(var c=db.getConnection();var p=c.prepareStatement("SELECT relative_path FROM rag_image_asset WHERE id=?")){p.setString(1,icon.assetId());try(var rows=p.executeQuery()){if(!rows.next()){missing(result,request,"物品图片资产未归档");continue;}relative=rows.getString(1);}}
                    if(!new ImageAssetStore(directory).available(icon.assetId(),relative)){missing(result,request,"物品图片缓存缺失或校验失败");continue;}
                    String key="i"+BuildRequest.fingerprint("item:"+profile+id).substring(0,20),sourceId="s"+BuildRequest.fingerprint("item:"+profile+id).substring(0,20);
                    if(pictures.stream().anyMatch(p->p.id().equals(key)))continue;
                    String proof="注册名："+id+"；实机默认物品图；不代表指定组件/NBT状态；环境："+profile;
                    addSource(sources,new AnswerArtifacts.Source(sourceId,"runtime-item:"+profile+":"+id,icon.name(),"mkb-runtime:"+profile+"/item_icons/"+id,"物品默认外观",null,"GAME_GUI",proof));
                    pictures.add(new AnswerArtifacts.Picture(key,icon.assetId(),relative,icon.name()+"（"+id+"；实机默认物品外观）",sourceId,"ITEM",List.of(),proof));continue;
                }
                var candidates=new ArrayList<JsonNode>();var equipment=equipment(request.query());
                for(var row:byId.getOrDefault(id,List.of())){
                    trail.scanned++;String reason=row.has("error")?"EXPORT_ERROR":!row.path("status").asText().equals("OK")?"NOT_VISIBLE":!EntityAppearanceState.matches(row,request.query())?"STATE_MISMATCH":!EntityAppearanceState.equipmentMatches(row,equipment)?"EQUIPMENT_MISMATCH":null;
                    trail.record(row,reason==null?"ELIGIBLE":"EXCLUDED",reason==null?"REGISTRY_AND_STATE_MATCH":reason);if(reason==null)candidates.add(row);
                }
                if(candidates.isEmpty()){
                    String reason="没有与请求状态匹配的实体图";if(!byId.containsKey(id))try(var c=db.getConnection();var p=c.prepareStatement("SELECT record_data FROM rag_runtime_record WHERE profile_id=? AND category='entity_exclusions' AND record_id=?")){p.setString(1,profile);p.setString(2,id);try(var rows=p.executeQuery()){if(rows.next())reason="导出时已排除："+new ObjectMapper().readTree(rows.getString(1)).path("reason").asText();}}
                    missing(result,request,reason);var absent=new ObjectMapper().createObjectNode().put("id",id);trail.record(absent,"EXCLUDED",byId.containsKey(id)?"NO_MATCHING_STATE":"NO_EXPORTED_ENTITY_RECORD");continue;
                }
                candidates.sort(Comparator.comparingInt((JsonNode candidate)->preference(candidate,request.query())).thenComparing(row->row.path("stateKey").asText("default")));var row=candidates.getFirst();
                for(int i=1;i<candidates.size();i++)trail.record(candidates.get(i),"EXCLUDED","LOWER_STATE_PREFERENCE");
                String state=EntityAppearanceState.key(row),key="e"+BuildRequest.fingerprint(profile+id+state+request.query()).substring(0,20),sourceId="s"+BuildRequest.fingerprint("entity:"+profile+id+state).substring(0,20);
                if(pictures.stream().anyMatch(p->p.id().equals(key)))continue;
                var asset=new ImageAssetStore(directory).put(storedBytes(EntityImageReferences.resolve(row,imageRows)));String label=EntityAppearanceState.label(row)+EntityCompositionInfo.caption(row);
                if(row.path("framing").asText().equals("POSSIBLY_CLIPPED"))label+="；取景可能不完整";else if(row.path("framing").asText().equals("UNSTABLE_FRAMING"))label+="；取景未收敛，待复核";
                String proof="注册名："+id+"；请求："+request.label()+"；状态键："+state+"；"+label;
                addSource(sources,new AnswerArtifacts.Source(sourceId,"runtime-entity:"+profile+":"+id+":"+state,request.label(),"mkb-runtime:"+profile+"/entity_icons/"+id+"/"+state,"导出实体状态",null,"GAME_GUI",proof));
                result.runtimeMediaStates.put(key,row);
                pictures.add(new AnswerArtifacts.Picture(key,asset.id(),asset.relativePath(),request.label()+"（"+id+"；"+label+"）",sourceId,"ENTITY",List.of(),proof));trail.selected++;trail.record(row,"SELECTED","REGISTRY_AND_STATE_MATCH");
            }catch(Exception failure){missing(result,request,"图片不可用："+failure.getClass().getSimpleName());}
        }
        result.artifacts=new AnswerArtifacts(result.artifacts.scopeId(),result.artifacts.scopeLabel(),sources,pictures,result.artifacts.citedSourceIds());result.entityImageAudit=trail.snapshot();
    }
    private static int preference(JsonNode row,String query){
        int original=EntityAppearanceState.preference(row);if(original<0||query.matches("(?is).*(age=|growth_stage=|幼年|成年|baby|adult|[一二三四五0-9]+阶).*"))return original;
        String age=row.path("state").path("traits").path("age").asText();int penalty=age.equals("baby")?2000:age.equals("juvenile")?1000:0;
        return penalty+original*10-row.path("state").path("traits").path("growthStage").asInt();
    }
    private static void addSource(List<AnswerArtifacts.Source> sources,AnswerArtifacts.Source source){if(sources.stream().noneMatch(s->s.id().equals(source.id())))sources.add(source);}
    private static void missing(QaPipeline.QaResult result,MediaRequestPlanner.Request request,String reason){result.mediaWarnings.add(request.label()+"："+reason+"，未用默认外观代替，也未使用其他对象。");}
    private Set<String> equipment(String question)throws Exception {
        var ids=new HashSet<String>();if(!question.matches("(?s).*(穿戴|穿着|戴着|手持|装备|持有).*"))return ids;
        var matcher=java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+").matcher(question);while(matcher.find())ids.add(matcher.group());
        String text=question.replaceAll("[a-z0-9_.-]+:[a-z0-9_./-]+"," ").toLowerCase(Locale.ROOT);
        record Hit(int start,int end,String id){}var hits=new ArrayList<Hit>();
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT item_id,display_name FROM rag_runtime_name WHERE profile_id=?")){p.setString(1,RuntimeKnowledgeStore.active(db));try(var rows=p.executeQuery()){while(rows.next()){String name=rows.getString(2).toLowerCase(Locale.ROOT);if(name.length()<2)continue;for(int start=text.indexOf(name);start>=0;start=text.indexOf(name,start+1))hits.add(new Hit(start,start+name.length(),rows.getString(1)));}}}
        hits.sort(Comparator.comparingInt((Hit hit)->hit.end()-hit.start()).reversed());var occupied=new BitSet(text.length());for(var hit:hits){int overlap=occupied.nextSetBit(hit.start());if(overlap>=0&&overlap<hit.end())continue;occupied.set(hit.start(),hit.end());ids.add(hit.id());}return ids;
    }
    String evidence(QaPipeline.QaResult result,String previous){
        var text=new StringBuilder(Objects.toString(previous,""));text.append("\n[Allowed runtime media: metadata only, not visual inspection or gameplay evidence]\n");
        for(var p:result.artifacts.pictures())if(Set.of("ENTITY","ITEM","RECIPE").contains(p.role()))text.append("[[image:").append(p.id()).append("]] ").append(p.caption()).append("；").append(p.context(),0,Math.min(1800,p.context().length())).append('\n');
        for(String warning:result.mediaWarnings)text.append("Media limitation: ").append(warning).append('\n');
        text.append("Insert each image only next to its matching object/state/recipe. For comparisons keep every state distinct, even when images share pixels. Never claim an unavailable state exists. Images are independently scaled; do not infer actual size, stats, recipes or drops from appearance.\n");return text.toString();
    }
    void finish(QaPipeline.QaResult result,String question){
        String answer=Objects.toString(result.answer,"");var used=new HashSet<String>();var matcher=java.util.regex.Pattern.compile("!\\[[^\\]\\r\\n]*]\\(kb-image:([a-zA-Z0-9]+)\\)").matcher(AnswerMedia.referenceText(answer));while(matcher.find())used.add(matcher.group(1));
        var kept=new ArrayList<AnswerArtifacts.Picture>();var additions=new ArrayList<String>();boolean visual=question.matches("(?is).*(展示|外观|图片|配图|长什么样|比较|对比|区别|分别|show|look).*" );
        for(var picture:result.artifacts.pictures()){
            if(!Set.of("ENTITY","ITEM","RECIPE").contains(picture.role())){kept.add(picture);continue;}
            var request=result.mediaPlan==null?null:result.mediaPlan.requests().stream().filter(r->picture.context().contains(r.subject().registry())).findFirst().orElse(null);
            boolean discussed=request!=null&&(answer.contains(request.subject().registry())||request.subject().names().stream().filter(n->n.length()>1||question.contains(n)).anyMatch(answer::contains));
            boolean show=visual||discussed||picture.role().equals("RECIPE");String marker="!["+picture.caption().replaceAll("[\\[\\]\\r\\n]"," ")+"](kb-image:"+picture.id()+")";
            if(used.contains(picture.id())&&request!=null&&!associated(answer,picture,request,result.runtimeMediaStates.get(picture.id()))){
                answer=answer.replaceAll("!\\[[^\\]\\r\\n]*]\\(kb-image:"+java.util.regex.Pattern.quote(picture.id())+"\\)","");used.remove(picture.id());
            }
            if(!show){answer=answer.replaceAll("!\\[[^\\]\\r\\n]*]\\(kb-image:"+java.util.regex.Pattern.quote(picture.id())+"\\)","");continue;}kept.add(picture);
            if(!used.contains(picture.id()))additions.add(marker);
        }
        if(!additions.isEmpty())answer+="\n\n"+(result.mediaPlan!=null&&result.mediaPlan.comparison()?"外观对照：":"参考配图：")+"\n\n"+String.join("\n\n",additions);
        if(result.mediaPlan!=null&&result.mediaPlan.comparison()&&kept.stream().filter(p->p.role().equals("ENTITY")).count()>1)answer+="\n\n各图独立缩放，画面占比不代表实际体型比例；外观相同的状态可能共用图像。";
        if(!result.mediaWarnings.isEmpty())answer+="\n\n配图说明：\n"+result.mediaWarnings.stream().distinct().map(w->"- "+w).collect(java.util.stream.Collectors.joining("\n"));
        result.answer=answer;result.artifacts=new AnswerArtifacts(result.artifacts.scopeId(),result.artifacts.scopeLabel(),result.artifacts.sources(),kept,result.artifacts.citedSourceIds());
    }
    private static boolean associated(String answer,AnswerArtifacts.Picture picture,MediaRequestPlanner.Request request,JsonNode state){
        var paragraphs=answer.split("\\n\\s*\\n");String text="";
        for(int i=0;i<paragraphs.length;i++)if(paragraphs[i].contains("kb-image:"+picture.id()+")")){
            text=paragraphs[i].replaceAll("!\\[[^\\]\\r\\n]*]\\(kb-image:[^)]+\\)","");
            if(text.isBlank())for(int j=i-1;j>=Math.max(0,i-3);j--)if(!paragraphs[j].contains("kb-image:")&&!paragraphs[j].isBlank()){text=paragraphs[j];break;}break;
        }
        boolean object=text.contains(request.subject().registry());for(String name:request.subject().names())if(!name.isBlank()&&text.contains(name))object=true;if(!object)return false;
        if(picture.role().equals("ITEM")&&text.contains("实体")&&!text.contains("物品"))return false;
        if(state!=null){
            try{Boolean axes=EntityVariantQuery.matches(state,text);if(Boolean.FALSE.equals(axes))return false;if((axes!=null||EntityAppearanceState.specific(text))&&!EntityAppearanceState.matches(state,text))return false;}
            catch(IOException invalid){return false;}
        }
        var actual=java.util.regex.Pattern.compile("成长阶段：([0-9]+)").matcher(picture.context());if(actual.find()){
            String stage=actual.group(1);var wanted=java.util.regex.Pattern.compile("([一二三四五0-9]+)阶").matcher(text);boolean any=false,match=false;while(wanted.find()){any=true;String value=wanted.group(1);String[] words={"一","二","三","四","五"};for(int n=0;n<5;n++)if(value.equals(words[n]))value=Integer.toString(n+1);if(value.equals(stage))match=true;}if(any&&!match)return false;
        }
        if(picture.caption().contains("幼年")&&text.contains("成年")&&!text.contains("幼年"))return false;
        if(picture.caption().contains("成年")&&text.contains("幼年")&&!text.contains("成年"))return false;
        return true;
    }
    void attach(QaPipeline.QaResult result,String question){prepare(result,question);finish(result,question);}
}
