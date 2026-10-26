package yagen.waitmydawn.kb.renderer;

import yagen.waitmydawn.kb.model.RecipePlan;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/** 与游戏 GUI 资源无关的配方卡片；材料约束和图标来源始终可见。 */
public final class RecipeCardRenderer {
    public static final String VERSION = "recipe-card-v3.1";
    public record Visual(String itemId,String name,BufferedImage image,String source,String detail) {}
    public record Input(RecipePlan.Slot slot,Visual visual,String description) {}
    public record Card(RecipePlan plan,List<Input> inputs,Visual output,List<String> metadata,List<String> notes) {}
    private static final Color INK=new Color(28,43,65), MUTED=new Color(80,96,117), BLUE=new Color(40,104,204);
    private static final Font TEXT=new Font(Font.SANS_SERIF,Font.PLAIN,16), SMALL=new Font(Font.SANS_SERIF,Font.PLAIN,14);
    private RecipeCardRenderer() {}

    public static BufferedImage render(Card card) {
        var grouped=new LinkedHashMap<String,Input>(); var totals=new LinkedHashMap<String,Integer>();
        for(var input:card.inputs()) {String key=input.slot().ingredient().identity();grouped.putIfAbsent(key,input);totals.merge(key,input.slot().ingredient().count(),Integer::sum);}
        var sample=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);var measure=sample.createGraphics();measure.setFont(TEXT);
        var rows=new ArrayList<String>();
        for(var entry:grouped.entrySet())rows.addAll(wrap("×"+totals.get(entry.getKey())+"  "+entry.getValue().description(),measure.getFontMetrics(),956));
        var notes=new ArrayList<String>();var allNotes=new LinkedHashSet<>(card.plan().notes());allNotes.addAll(card.notes());
        for(String note:allNotes)notes.addAll(wrap(note,measure.getFontMetrics(),956));measure.dispose();
        int legendY=386,notesY=legendY+34+Math.max(1,rows.size())*24+20,height=notesY+30+notes.size()*24+28;
        var image=new BufferedImage(1040,height,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setColor(new Color(244,247,252));g.fillRect(0,0,1040,height);g.setColor(Color.WHITE);g.fillRoundRect(12,12,1016,height-24,24,24);
            g.setColor(INK);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,25));
            String title=card.plan().output().dynamic()?"动态纹饰结果":Objects.toString(card.output().name(),card.plan().output().itemId());
            g.drawString(elide(title,g.getFontMetrics(),660),32,49);
            g.setFont(TEXT);g.setColor(BLUE);String type=card.plan().kind().station+" · "+card.plan().kind().label;
            g.drawString(type,1008-g.getFontMetrics().stringWidth(type),47);
            g.setColor(new Color(224,232,243));g.drawLine(32,70,1008,70);
            boolean crafting=card.plan().kind()==RecipePlan.Kind.SHAPED||card.plan().kind()==RecipePlan.Kind.SHAPELESS;
            boolean smithing=card.plan().kind()==RecipePlan.Kind.SMITHING_TRANSFORM||card.plan().kind()==RecipePlan.Kind.SMITHING_TRIM;
            if(crafting) {
                for(int i=0;i<9;i++)backgroundSlot(g,32+(i%3)*80,98+(i/3)*80,76);
                for(var input:card.inputs())drawSlot(g,input.visual(),input.slot().ingredient().count(),32+(input.slot().index()%3)*80,98+(input.slot().index()/3)*80,76,choice(input.slot().ingredient()));
            } else if(smithing) {
                for(var input:card.inputs()) {int x=32+input.slot().index()*96;g.setFont(TEXT);g.setColor(MUTED);g.drawString(input.slot().role(),x,148);backgroundSlot(g,x,164,76);drawSlot(g,input.visual(),input.slot().ingredient().count(),x,164,76,choice(input.slot().ingredient()));}
            } else {
                g.setFont(TEXT);g.setColor(MUTED);g.drawString("原料",144,148);backgroundSlot(g,144,164,76);
                var input=card.inputs().getFirst();drawSlot(g,input.visual(),input.slot().ingredient().count(),144,164,76,choice(input.slot().ingredient()));
                g.setFont(SMALL);g.drawString(card.plan().kind()==RecipePlan.Kind.CAMPFIRE?"无需燃料":card.plan().kind()==RecipePlan.Kind.STONECUTTING?"切石加工":"燃料另计",318,257);
            }
            g.setStroke(new BasicStroke(5,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setColor(new Color(116,141,175));g.drawLine(340,210,424,210);g.drawLine(407,195,424,210);g.drawLine(407,225,424,210);
            backgroundSlot(g,460,162,96);drawSlot(g,card.output(),card.plan().output().dynamic()?1:card.plan().output().count(),460,162,96,false);
            g.setFont(SMALL);g.setColor(MUTED);g.drawString(card.plan().output().dynamic()?"动态产物":"产物 ×"+card.plan().output().count(),460,284);
            g.setColor(new Color(243,247,255));g.fillRoundRect(602,94,406,252,14,14);int metadataY=120;g.setFont(SMALL);
            for(String text:card.metadata())for(String line:wrap(text,g.getFontMetrics(),370)){if(metadataY>329)break;g.setColor(MUTED);g.drawString(line,620,metadataY);metadataY+=21;}
            if(card.plan().cookingTicks()!=null){g.setColor(MUTED);g.drawString("耗时："+card.plan().cookingTicks()+" tick（"+card.plan().cookingTicks()/20.0+" 秒）",32,362);}
            if(card.plan().experience()!=null){g.setColor(MUTED);g.drawString("经验："+card.plan().experience(),330,362);}
            g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,18));g.setColor(INK);g.drawString("材料与候选",32,legendY);g.setFont(TEXT);g.setColor(MUTED);int y=legendY+30;
            for(String row:rows){g.drawString(row,42,y);y+=24;}
            g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,17));g.setColor(INK);g.drawString("依据与说明",32,notesY);g.setFont(TEXT);g.setColor(MUTED);y=notesY+28;
            for(String note:notes){g.drawString(note,42,y);y+=24;}
        } finally {g.dispose();}
        return image;
    }
    private static boolean choice(RecipePlan.Ingredient ingredient){return ingredient.alternatives().size()>1||ingredient.alternatives().stream().anyMatch(RecipePlan.Alternative::tag);}
    private static void backgroundSlot(Graphics2D g,int x,int y,int size){g.setColor(new Color(235,240,248));g.fillRoundRect(x,y,size,size,10,10);g.setStroke(new BasicStroke(1));g.setColor(new Color(207,218,233));g.drawRoundRect(x,y,size,size,10,10);}
    private static void drawSlot(Graphics2D g,Visual icon,int count,int x,int y,int size,boolean alternative){
        int inset=size==96?8:6,iconSize=size-inset*2;
        if(icon.image()!=null)g.drawImage(icon.image(),x+inset,y+inset,iconSize,iconSize,null);
        else {g.setColor(new Color(255,239,214));g.fillRoundRect(x+inset,y+inset,iconSize,iconSize,8,8);g.setColor(new Color(156,99,23));g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,32));g.drawString("?",x+size/2-10,y+size/2+11);}
        if(alternative){g.setColor(BLUE);g.fillRoundRect(x+2,y+2,34,19,6,6);g.setColor(Color.WHITE);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,12));g.drawString("任选",x+6,y+16);}
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
