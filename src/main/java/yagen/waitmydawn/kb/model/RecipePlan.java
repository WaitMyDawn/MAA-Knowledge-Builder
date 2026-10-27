package yagen.waitmydawn.kb.model;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** 配方解析和绘图共用的槽位语义；标签、替代材料与有状态结果不能退化成普通物品 ID。 */
public record RecipePlan(Kind kind, List<Slot> slots, Output output, int width, int height,
                         Integer cookingTicks, Double experience, List<String> notes) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public enum Kind {
        SHAPED("有序合成", "工作台"), SHAPELESS("无序合成", "工作台"),
        SMELTING("熔炼", "熔炉"), BLASTING("高炉冶炼", "高炉"), SMOKING("烟熏", "烟熏炉"),
        CAMPFIRE("营火烹饪", "营火"), STONECUTTING("切石", "切石机"),
        SMITHING_TRANSFORM("锻造升级", "锻造台"), SMITHING_TRIM("盔甲纹饰", "锻造台");
        public final String label, station;
        Kind(String label, String station) { this.label = label; this.station = station; }
    }
    public record Alternative(String id, boolean tag) {
        public String label() { return (tag ? "#" : "") + id; }
    }
    public record Ingredient(List<Alternative> alternatives, int count, boolean stateful, String unsupported,JsonNode state) {
        public Ingredient(List<Alternative> alternatives,int count,boolean stateful,String unsupported){this(alternatives,count,stateful,unsupported,null);}
        public Ingredient { alternatives = List.copyOf(alternatives); }
        public String identity() { return alternatives + "|" + stateful + "|" + unsupported+"|"+state; }
    }
    public record Slot(int index, String role, Ingredient ingredient) {}
    public record Output(String itemId, int count, boolean stateful, boolean dynamic,JsonNode state) {}
    public RecipePlan { slots = List.copyOf(slots); notes = List.copyOf(notes); }

    public static RecipePlan parse(String raw) {
        try {
            if (raw == null || raw.length() > 2 * 1024 * 1024) throw new IllegalArgumentException("配方文本为空或超限");
            JsonNode root = JSON.readTree(raw);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("配方必须为对象");
            String type = root.path("type").asText("");
            if (!type.contains(":")) type = "minecraft:" + type;
            Kind kind = switch (type) {
                case "minecraft:crafting_shaped" -> Kind.SHAPED;
                case "minecraft:crafting_shapeless" -> Kind.SHAPELESS;
                case "minecraft:smelting" -> Kind.SMELTING;
                case "minecraft:blasting" -> Kind.BLASTING;
                case "minecraft:smoking" -> Kind.SMOKING;
                case "minecraft:campfire_cooking" -> Kind.CAMPFIRE;
                case "minecraft:stonecutting" -> Kind.STONECUTTING;
                case "minecraft:smithing_transform" -> Kind.SMITHING_TRANSFORM;
                case "minecraft:smithing_trim" -> Kind.SMITHING_TRIM;
                default -> throw new IllegalArgumentException("未适配配方类型：" + type);
            };
            var notes = new ArrayList<String>();
            if (root.has("conditions") || root.has("neoforge:conditions") || root.has("forge:conditions")) notes.add("配方包含条件；未验证整合包中是否生效。");
            var slots = new ArrayList<Slot>(); int width = 1, height = 1;
            if (kind == Kind.SHAPED) {
                var pattern = root.path("pattern"); var keys = root.path("key");
                if (!pattern.isArray() || pattern.isEmpty() || pattern.size() > 3 || !keys.isObject()) throw new IllegalArgumentException("有序配方缺少有效 pattern/key");
                var rows = new ArrayList<String>(); int originalWidth = -1;
                for (var row : pattern) {
                    if (!row.isTextual() || row.asText().isEmpty() || row.asText().length() > 3) throw new IllegalArgumentException("配方行宽必须为 1–3");
                    if (originalWidth >= 0 && originalWidth != row.asText().length()) throw new IllegalArgumentException("有序配方行宽不一致");
                    originalWidth = row.asText().length(); rows.add(row.asText());
                }
                int top = 0, bottom = rows.size() - 1, left = originalWidth, right = -1;
                while (top <= bottom && rows.get(top).isBlank()) top++;
                while (bottom >= top && rows.get(bottom).isBlank()) bottom--;
                for (int y = top; y <= bottom; y++) for (int x = 0; x < originalWidth; x++) if (rows.get(y).charAt(x) != ' ') { left = Math.min(left, x); right = Math.max(right, x); }
                if (right < left) throw new IllegalArgumentException("配方图案没有材料");
                var parsedKeys = new HashMap<Character, Ingredient>();
                var fields = keys.fields();
                while (fields.hasNext()) { var entry = fields.next(); if (entry.getKey().length() != 1 || entry.getKey().equals(" ")) throw new IllegalArgumentException("配方键必须为单个非空格字符"); parsedKeys.put(entry.getKey().charAt(0), ingredient(entry.getValue())); }
                var used = new HashSet<Character>();
                width = right - left + 1; height = bottom - top + 1;
                for (int y = top; y <= bottom; y++) for (int x = left; x <= right; x++) {
                    char symbol = rows.get(y).charAt(x); if (symbol == ' ') continue;
                    if (!parsedKeys.containsKey(symbol)) throw new IllegalArgumentException("配方符号未定义：" + symbol);
                    slots.add(new Slot((y - top) * 3 + x - left, "材料", parsedKeys.get(symbol))); used.add(symbol);
                }
                if (!used.equals(parsedKeys.keySet())) throw new IllegalArgumentException("配方包含未使用的材料键");
                if (width < 3 || height < 3) notes.add("图示保留相对位置；小尺寸图案可在工作台中平移。");
            } else if (kind == Kind.SHAPELESS) {
                var ingredients = root.path("ingredients");
                if (!ingredients.isArray() || ingredients.isEmpty() || ingredients.size() > 9) throw new IllegalArgumentException("无序配方材料必须为 1–9 项");
                for (int i = 0; i < ingredients.size(); i++) slots.add(new Slot(i, "材料", ingredient(ingredients.get(i))));
                width = Math.min(3, ingredients.size()); height = (ingredients.size() + 2) / 3;
                notes.add("无序合成：槽位仅用于展示，材料可任意摆放。");
            } else if (kind == Kind.SMITHING_TRANSFORM || kind == Kind.SMITHING_TRIM) {
                int i = 0; for (String role : List.of("template", "base", "addition")) slots.add(new Slot(i++, switch (role) { case "template" -> "模板"; case "base" -> "基底"; default -> "添加物"; }, ingredient(root.path(role))));
                width = 3;
                notes.add("锻造结果的属性或纹饰取决于基底与状态；默认图标不代表完整组件/NBT。");
            } else slots.add(new Slot(0, "原料", ingredient(root.path("ingredient"))));
            boolean dynamic = kind == Kind.SMITHING_TRIM;
            var result = root.path("result");
            if (result.isObject() && result.has("id") && result.has("item")) throw new IllegalArgumentException("产物不能同时指定 id 和 item");
            String id = dynamic ? null : result.isTextual() ? result.asText() : result.path("id").asText(result.path("item").asText(null));
            if (!dynamic) id = resourceId(id);
            int count = dynamic ? 1 : result.isObject() ? positiveCount(result.path("count"), 1) : positiveCount(root.path("count"), 1);
            boolean stateful = hasState(result);
            if (stateful) notes.add("产物带指定组件或 NBT，默认状态图标不能代替该状态。");
            Integer ticks = null; Double xp = null;
            if (Set.of(Kind.SMELTING, Kind.BLASTING, Kind.SMOKING, Kind.CAMPFIRE).contains(kind)) {
                ticks = positiveCount(root.path("cookingtime"), kind == Kind.CAMPFIRE ? 600 : kind == Kind.SMELTING ? 200 : 100);
                if (root.has("experience")) { xp = root.path("experience").asDouble(-1); if (!root.path("experience").isNumber() || !Double.isFinite(xp) || xp < 0) throw new IllegalArgumentException("经验值无效"); }
                notes.add("不指定燃料种类或数量；营火无需燃料，其他工作站燃料由游戏规则决定。");
            }
            return new RecipePlan(kind, slots, new Output(id, count, stateful, dynamic,state(result)), width, height, ticks, xp, notes);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("配方 JSON 无效", e); }
    }
    public static Ingredient ingredient(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) throw new IllegalArgumentException("缺少材料");
        if (node.isArray()) {
            if (node.isEmpty() || node.size() > 128) throw new IllegalArgumentException("替代材料数组为空或超限");
            var alternatives = new ArrayList<Alternative>(); boolean state = false; String unknown = null; Integer count = null;JsonNode exactState=null;boolean first=true,mixed=false;
            for (var value : node) {
                if (value.isArray()) throw new IllegalArgumentException("不支持嵌套替代材料数组");
                var child = ingredient(value); alternatives.addAll(child.alternatives()); state |= child.stateful();
                if(first){exactState=child.state();first=false;}else if(!Objects.equals(exactState,child.state()))mixed=true;
                if (child.unsupported() != null) unknown = child.unsupported();
                if (count != null && count != child.count()) throw new IllegalArgumentException("替代材料数量不一致"); count = child.count();
            }
            return new Ingredient(alternatives.stream().distinct().toList(), count, state, unknown,mixed?null:exactState);
        }
        if (node.isTextual()) { String text = node.asText(); return new Ingredient(List.of(new Alternative(resourceId(text.startsWith("#") ? text.substring(1) : text), text.startsWith("#"))), 1, false, null); }
        if (!node.isObject()) throw new IllegalArgumentException("材料必须为对象、字符串或数组");
        int count = positiveCount(node.path("count"), 1);
        if (node.has("item") && node.has("id")) throw new IllegalArgumentException("材料不能同时指定 item 和 id");
        if (node.has("type") && !node.path("type").asText().equals("minecraft:item")) return new Ingredient(List.of(), count, hasState(node), "自定义材料类型：" + node.path("type").asText());
        String item = node.path("item").asText(node.path("id").asText(null)); String tag = node.path("tag").asText(null);
        if ((item == null) == (tag == null)) throw new IllegalArgumentException("材料必须且只能指定 item/id 或 tag");
        return new Ingredient(List.of(new Alternative(resourceId(item != null ? item : tag), tag != null)), count, hasState(node), null,state(node));
    }
    private static JsonNode state(JsonNode node){
        if(!node.isObject())return null;
        if(node.has("components")&&node.has("nbt"))return null;
        if(node.path("components").isObject()&&!node.path("components").isEmpty())return JSON.createObjectNode().set("components",node.path("components").deepCopy());
        if(node.path("nbt").isTextual()&&!node.path("nbt").asText().isBlank())return JSON.createObjectNode().set("nbt",node.path("nbt").deepCopy());return null;
    }
    private static boolean hasState(JsonNode node) { return node.isObject() && (node.has("nbt") || node.has("components") && !node.path("components").isEmpty() || node.has("data")); }
    private static int positiveCount(JsonNode value, int fallback) {
        if (value.isMissingNode()) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1 || value.asInt() > 1_000_000) throw new IllegalArgumentException("数量或时间必须为 1–1000000 的整数");
        return value.asInt();
    }
    public static String resourceId(String id) {
        if (id == null || id.length() > 500 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || id.contains("..")) throw new IllegalArgumentException("无效注册名：" + id);
        return id;
    }
}
