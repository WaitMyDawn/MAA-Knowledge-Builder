package yagen.waitmydawn.kb.renderer;

import yagen.waitmydawn.kb.model.RecipePlan;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/** 与游戏 GUI 资源无关的配方卡片；材料约束和图标来源始终可见。 */
public final class RecipeCardRenderer {
    public static final String VERSION = "recipe-card-v3.3";
    public record Visual(String itemId,String name,BufferedImage image,String source,String detail) {}
    public record Input(RecipePlan.Slot slot,Visual visual,String description) {}
    public record Card(RecipePlan plan,List<Input> inputs,Visual output,List<String> metadata,List<String> notes) {}
    private static final Color INK=new Color(26,29,33), MUTED=new Color(48,53,60), BLUE=new Color(24,76,145);
    private static final Font TEXT=new Font(Font.SANS_SERIF,Font.PLAIN,22), SMALL=new Font(Font.SANS_SERIF,Font.PLAIN,20);
    private static final int LINE_HEIGHT=32;
    private RecipeCardRenderer() {}

    public static BufferedImage render(Card card) {
        var grouped=new LinkedHashMap<String,Input>(); var totals=new LinkedHashMap<String,Integer>();
        for(var input:card.inputs()) {String key=input.slot().ingredient().identity();grouped.putIfAbsent(key,input);totals.merge(key,input.slot().ingredient().count(),Integer::sum);}
        var sample=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);var measure=sample.createGraphics();measure.setFont(TEXT);
        var rows=new ArrayList<String>();
        for(var entry:grouped.entrySet())rows.addAll(wrap("×"+totals.get(entry.getKey())+"  "+entry.getValue().description(),measure.getFontMetrics(),956));
        var notes=new ArrayList<String>();var allNotes=new LinkedHashSet<>(card.plan().notes());allNotes.addAll(card.notes());
        for(String note:allNotes)notes.addAll(wrap(note,measure.getFontMetrics(),956));
        measure.setFont(SMALL);var metadata=new ArrayList<String>();
        for(String entry:card.metadata())metadata.addAll(wrap(entry,measure.getFontMetrics(),940));measure.dispose();
        int legendY=448,notesY=legendY+40+Math.max(1,rows.size())*LINE_HEIGHT+24;
        int metadataY=notesY+40+notes.size()*LINE_HEIGHT+24;
        int metadataHeight=60+metadata.size()*30,height=metadataY+metadataHeight+32;
        var image=new BufferedImage(1040,height,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setColor(new Color(244,247,252));g.fillRect(0,0,1040,height);g.setColor(Color.WHITE);g.fillRoundRect(12,12,1016,height-24,24,24);
            g.setColor(INK);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,32));
            String title=card.plan().output().dynamic()?"动态纹饰结果":Objects.toString(card.output().name(),card.plan().output().itemId());
            g.drawString(elide(title,g.getFontMetrics(),660),32,49);
            g.setFont(TEXT);g.setColor(BLUE);String type=card.plan().kind().station+" · "+card.plan().kind().label;
            g.drawString(type,1008-g.getFontMetrics().stringWidth(type),47);
            g.setColor(new Color(224,232,243));g.drawLine(32,70,1008,70);
            boolean crafting=card.plan().kind()==RecipePlan.Kind.SHAPED||card.plan().kind()==RecipePlan.Kind.SHAPELESS;
            boolean smithing=card.plan().kind()==RecipePlan.Kind.SMITHING_TRANSFORM||card.plan().kind()==RecipePlan.Kind.SMITHING_TRIM;
            g.setColor(new Color(198,198,198));g.fillRect(68,88,764,316);
            g.setColor(Color.WHITE);g.fillRect(68,88,764,4);g.fillRect(68,88,4,316);
            g.setColor(new Color(85,85,85));g.fillRect(68,400,764,4);g.fillRect(828,88,4,316);
            if(crafting) {
                for(int i=0;i<9;i++)backgroundSlot(g,148+(i%3)*92,112+(i/3)*92,88);
                for(var input:card.inputs())drawSlot(g,input.visual(),input.slot().ingredient().count(),148+(input.slot().index()%3)*92,112+(input.slot().index()/3)*92,88,choice(input.slot().ingredient()));
            } else if(smithing) {
                for(var input:card.inputs()) {int x=100+input.slot().index()*116;g.setFont(TEXT);g.setColor(MUTED);g.drawString(input.slot().role(),x,158);backgroundSlot(g,x,184,88);drawSlot(g,input.visual(),input.slot().ingredient().count(),x,184,88,choice(input.slot().ingredient()));}
            } else {
                g.setFont(TEXT);g.setColor(MUTED);g.drawString("原料",264,158);backgroundSlot(g,264,184,88);
                var input=card.inputs().getFirst();drawSlot(g,input.visual(),input.slot().ingredient().count(),264,184,88,choice(input.slot().ingredient()));
                g.setFont(SMALL);g.drawString(card.plan().kind()==RecipePlan.Kind.CAMPFIRE?"无需燃料":card.plan().kind()==RecipePlan.Kind.STONECUTTING?"切石加工":"燃料另计",264,312);
            }
            g.setStroke(new BasicStroke(6,BasicStroke.CAP_SQUARE,BasicStroke.JOIN_MITER));g.setColor(new Color(80,80,80));g.drawLine(472,246,584,246);g.drawLine(562,224,584,246);g.drawLine(562,268,584,246);
            backgroundSlot(g,652,190,112);drawSlot(g,card.output(),card.plan().output().dynamic()?1:card.plan().output().count(),652,190,112,false);
            g.setFont(SMALL);g.setColor(MUTED);g.drawString(card.plan().output().dynamic()?"动态产物":"产物 ×"+card.plan().output().count(),652,336);
            if(card.plan().cookingTicks()!=null){g.setColor(MUTED);g.drawString("耗时："+card.plan().cookingTicks()+" tick（"+card.plan().cookingTicks()/20.0+" 秒）",148,372);}
            if(card.plan().experience()!=null){g.setColor(MUTED);g.drawString("经验："+card.plan().experience(),652,372);}
            g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,25));g.setColor(INK);g.drawString("材料与候选",32,legendY);g.setFont(TEXT);g.setColor(MUTED);int y=legendY+36;
            for(String row:rows){g.drawString(row,42,y);y+=LINE_HEIGHT;}
            g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,25));g.setColor(INK);g.drawString("依据与说明",32,notesY);g.setFont(TEXT);g.setColor(MUTED);y=notesY+36;
            for(String note:notes){g.drawString(note,42,y);y+=LINE_HEIGHT;}
            g.setColor(new Color(237,239,242));g.fillRoundRect(32,metadataY,976,metadataHeight,12,12);
            g.setColor(INK);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,22));g.drawString("配方来源与图标环境",48,metadataY+32);
            g.setFont(SMALL);g.setColor(MUTED);y=metadataY+66;
            for(String line:metadata){g.drawString(line,48,y);y+=30;}
        } finally {g.dispose();}
        return image;
    }
    private static boolean choice(RecipePlan.Ingredient ingredient){return ingredient.alternatives().size()>1||ingredient.alternatives().stream().anyMatch(RecipePlan.Alternative::tag);}
    private static void backgroundSlot(Graphics2D g,int x,int y,int size){
        // 原版容器槽以暗上沿、亮下沿形成凹陷，按整数像素绘制以保持缩放后的轮廓。
        int edge=4;g.setColor(new Color(139,139,139));g.fillRect(x,y,size,size);
        g.setColor(new Color(55,55,55));g.fillRect(x,y,size,edge);g.fillRect(x,y,edge,size);
        g.setColor(Color.WHITE);g.fillRect(x,y+size-edge,size,edge);g.fillRect(x+size-edge,y,edge,size);
        g.setColor(new Color(198,198,198));g.fillRect(x+size-edge,y,edge,edge);g.fillRect(x,y+size-edge,edge,edge);
    }
    private static void drawSlot(Graphics2D g,Visual icon,int count,int x,int y,int size,boolean alternative){
        int inset=8,iconSize=size-inset*2;
        if(icon.source().equals("NATIVE_TRANSPARENT")){g.setColor(new Color(48,48,48));g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,20));g.drawString("透明",x+size/2-20,y+size/2+7);}
        else if(icon.image()!=null)g.drawImage(icon.image(),x+inset,y+inset,iconSize,iconSize,null);
        else {g.setColor(new Color(48,48,48));g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,40));g.drawString("?",x+size/2-12,y+size/2+14);}
        if(alternative){g.setColor(BLUE);g.fillRect(x+4,y+4,42,24);g.setColor(Color.WHITE);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,16));g.drawString("任选",x+9,y+23);}
        if(count>1){String text=Integer.toString(count);int fontSize=25;g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,fontSize));while(fontSize>10&&g.getFontMetrics().stringWidth(text)>size-8)g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,--fontSize));int tx=x+size-5-g.getFontMetrics().stringWidth(text),ty=y+size-6;g.setColor(new Color(20,30,45));g.drawString(text,tx+1,ty+1);g.drawString(text,tx-1,ty-1);g.setColor(Color.WHITE);g.drawString(text,tx,ty);}
    }
    private static List<String> wrap(String text,FontMetrics metrics,int width){
        var lines=new ArrayList<String>();var line=new StringBuilder();
        for(int cp:Objects.toString(text,"").codePoints().toArray()){String glyph=new String(Character.toChars(cp));if(cp=='\n'||metrics.stringWidth(line+glyph)>width){lines.add(line.toString());line.setLength(0);if(cp=='\n')continue;}line.append(glyph);}
        if(!line.isEmpty())lines.add(line.toString());return lines;
    }
    private static String elide(String value,FontMetrics metrics,int width){if(metrics.stringWidth(value)<=width)return value;int end=value.length();while(end>0&&metrics.stringWidth(value.substring(0,end)+"…")>width)end--;return value.substring(0,end)+"…";}
    public static BufferedImage renderBasic(String recipe,Map<String,BufferedImage> images){
        var plan=RecipePlan.parse(recipe);var inputs=new ArrayList<Input>();
        for(var slot:plan.slots()){
            String id=slot.ingredient().alternatives().stream().filter(a->!a.tag()).map(RecipePlan.Alternative::id).filter(images::containsKey).findFirst().orElse(slot.ingredient().alternatives().isEmpty()?null:slot.ingredient().alternatives().getFirst().label());
            var icon=new Visual(id,id==null?"未确定物品":id,slot.ingredient().stateful()||id==null?null:images.get(id),"STATIC","仅展示提供的精确注册名图标");inputs.add(new Input(slot,icon,slot.ingredient().alternatives().toString()));
        }
        var out=plan.output();var icon=new Visual(out.itemId(),out.dynamic()?"动态产物":out.itemId(),out.stateful()||out.dynamic()?null:images.get(out.itemId()),"STATIC","默认图标");
        return render(new Card(plan,inputs,icon,List.of("默认物品状态"),List.of("未提供可解析的标签环境时，标签保持未解析。")));
    }
}
