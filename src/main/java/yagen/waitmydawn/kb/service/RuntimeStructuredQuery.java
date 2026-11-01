package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.BuildRequest;
import java.sql.*;
import java.util.*;

/** 运行时规则查询不依赖向量行；派生索引可由已发布记录重建，不参与内容指纹或快照发布。 */
public final class RuntimeStructuredQuery {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int INDEX_VERSION = 7;
    private static final Object INDEX_LOCK = new Object();
    private static final Set<String> INDEXED = Set.of("item_attributes", "entity_attributes", "entity_state_attributes",
            "loot_tables", "loot_modifiers", "loot_entrypoints", "acquisition_index", "structures", "structure_sets",
            "template_pools", "structure_processors", "structure_templates", "multiblocks", "recipes", "trades", "brewing", "workstation_rules", "potions", "effects", "enchantments", "spawn_rules", "registries", "language", "mod_metadata", "entity_icons", "tags", "entity_exclusions");
    public enum Status { FOUND, NOT_FOUND, CAPABILITY_MISSING, PARTIAL, FAILED }
    public record Key(String category, String id) {
        public Key {
            if (category == null || id == null || id.isBlank() || id.length() > 1100 || !(INDEXED.contains(category) || category.equals("registries")))
                throw new IllegalArgumentException("Invalid structured record key");
        }
    }
    public record Row(Key key, JsonNode data) {}
    public record State(String key, Boolean baby, Boolean equipped, String origin) {
        public static State any() { return new State(null, null, null, null); }
    }
    public record Page(String profile, String category, Status status, String coverage, int total,
                       int nextOffset, List<Row> rows, List<String> warnings) {
        public Page { rows = List.copyOf(rows); warnings = List.copyOf(warnings); }
    }
    public record Link(Key from, String relation, Key to) {}
    public record Links(List<Link> links, int total, int nextOffset) {
        public Links { links = List.copyOf(links); }
    }
    public record Expansion(List<Row> rows, List<Link> links, boolean truncated, List<String> warnings) {
        public Expansion { rows = List.copyOf(rows); links = List.copyOf(links); warnings = List.copyOf(warnings); }
    }
    private final DatabaseBuilder db;
    private final String profile;
    private JsonNode declarations;
    private JsonNode environment;

    public RuntimeStructuredQuery(DatabaseBuilder db, String profile) {
        this.db = Objects.requireNonNull(db); this.profile = Objects.requireNonNull(profile);
    }
    public JsonNode environment()throws java.sql.SQLException,java.io.IOException {
        if(environment==null){var identity=JSON.createObjectNode().put("profile",profile);
            try(var c=db.getConnection();var p=c.prepareStatement("SELECT manifest_json FROM rag_item_icon_profile WHERE id=?")){p.setString(1,profile);try(var rows=p.executeQuery()){if(rows.next()){var source=JSON.readTree(rows.getString(1)).path("environment");for(String field:List.of("minecraftVersion","loader","loaderVersion"))if(source.path(field).isValueNode())identity.set(field,source.path(field));}}}
            environment=identity;
        }
        return environment.deepCopy();
    }
    static void init(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS rag_runtime_fact(profile_id VARCHAR(64),category VARCHAR(40),record_id VARCHAR(1100),field VARCHAR(64),fact_value VARCHAR(1000),PRIMARY KEY(profile_id,category,field,fact_value,record_id))");
            s.execute("CREATE TABLE IF NOT EXISTS rag_runtime_query_version(profile_id VARCHAR(64) PRIMARY KEY, version INT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_runtime_lookup(profile_id VARCHAR(64) NOT NULL, category VARCHAR(40) NOT NULL, record_id VARCHAR(1100) NOT NULL, subject_id VARCHAR(1100) NOT NULL, state_key VARCHAR(100), baby BOOLEAN, equipped BOOLEAN, origin VARCHAR(100), PRIMARY KEY(profile_id,category,record_id))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_runtime_lookup_subject ON rag_runtime_lookup(profile_id,category,subject_id,state_key)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_runtime_link(profile_id VARCHAR(64) NOT NULL, from_category VARCHAR(40) NOT NULL, from_id VARCHAR(1100) NOT NULL, relation VARCHAR(64) NOT NULL, to_category VARCHAR(40) NOT NULL, to_id VARCHAR(1100) NOT NULL, PRIMARY KEY(profile_id,from_category,from_id,relation,to_category,to_id))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_runtime_link_target ON rag_runtime_link(profile_id,to_category,to_id)");
        }
    }
    public static boolean supports(String category) { return INDEXED.contains(category); }
    private static void category(String value) {
        if (!INDEXED.contains(value)) throw new IllegalArgumentException("Unsupported structured category: " + value);
    }
    private static void bounds(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid structured query page");
    }
    private String coverage(String category) throws Exception {
        if (declarations == null) try (var c = db.getConnection(); var p = c.prepareStatement("SELECT descriptor FROM rag_runtime_snapshot WHERE profile_id=?")) {
            p.setString(1, profile);
            try (var r = p.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Snapshot profile not found");
                declarations = JSON.readTree(r.getString(1)).path("categories");
            }
        }
        return category.equals("acquisition_index") ? "DERIVED_CANDIDATES" : declarations.path(category).path("status").asText("NOT_EXPORTED");
    }
    public Page get(String category, String id) throws Exception {
        new Key(category, id);
        return read(category, id, null, State.any(), 0, 1,Map.of());
    }
    public Page page(String category, String subject, State state, int offset, int limit) throws Exception {
        if (subject != null && (subject.isBlank() || subject.length() > 1100)) throw new IllegalArgumentException("Invalid subject ID");
        return read(category, null, subject, Objects.requireNonNull(state), offset, limit,Map.of());
    }
    public Page query(String category,Map<String,String> filters,int offset,int limit)throws Exception {
        return states(category,null,State.any(),filters,offset,limit);
    }
    public Page states(String category,String subject,State state,Map<String,String> filters,int offset,int limit)throws Exception {
        if(filters.keySet().stream().anyMatch(key->!Set.of("id","entity","profession","level","station","outputPotionId","inputPotionId","source","lootTable").contains(key)&&!key.matches("state\\.[a-zA-Z0-9_]{1,40}")))throw new IllegalArgumentException("Unsupported query fields");
        return read(category,null,subject,state,offset,limit,Map.copyOf(filters));
    }
    private Page read(String category, String id, String subject, State state, int offset, int limit,Map<String,String> filters) throws Exception {
        long started = System.nanoTime();
        category(category); bounds(offset, limit); String coverage = coverage(category);
        if (!Set.of("COMPLETE", "COMPLETE_WITH_ERRORS", "DERIVED_CANDIDATES").contains(coverage))
            return new Page(profile, category, Status.CAPABILITY_MISSING, coverage, 0, -1, List.of(), List.of("类别未完整导出；未命中不能解释为游戏中不存在。"));
        boolean filtered = subject != null || state.key() != null || state.baby() != null || state.equipped() != null || state.origin() != null;
        if (filtered||!filters.isEmpty()) ensureIndex();
        String from = " FROM rag_runtime_record r" + (filtered ? " JOIN rag_runtime_lookup l ON l.profile_id=r.profile_id AND l.category=r.category AND l.record_id=r.record_id" : "");
        var parameters = new ArrayList<Object>(); parameters.add(profile); parameters.add(category);
        String where = " WHERE r.profile_id=? AND r.category=?";
        if (id != null) { where += " AND r.record_id=?"; parameters.add(id); }
        if (subject != null) { where += " AND l.subject_id=?"; parameters.add(subject); }
        if (state.key() != null) { where += " AND l.state_key=?"; parameters.add(state.key()); }
        if (state.baby() != null) { where += " AND l.baby=?"; parameters.add(state.baby()); }
        if (state.equipped() != null) { where += " AND l.equipped=?"; parameters.add(state.equipped()); }
        if (state.origin() != null) { where += " AND l.origin=?"; parameters.add(state.origin()); }
        for(var filter:new TreeMap<>(filters).entrySet()) {
            where+=" AND EXISTS(SELECT 1 FROM rag_runtime_fact f WHERE f.profile_id=r.profile_id AND f.category=r.category AND f.record_id=r.record_id AND f.field=? AND f.fact_value=?)";
            parameters.add(filter.getKey());parameters.add(filter.getValue());
        }
        var rows = new ArrayList<Row>(); int total;
        try (var c = db.getConnection(); var count = c.prepareStatement("SELECT COUNT(*)" + from + where);
             var query = c.prepareStatement("SELECT r.record_id,r.record_data" + from + where + " ORDER BY r.record_id LIMIT ? OFFSET ?")) {
            bind(count, parameters); try (var r = count.executeQuery()) { r.next(); total = r.getInt(1); }
            bind(query, parameters); query.setInt(parameters.size() + 1, limit); query.setInt(parameters.size() + 2, offset);
            try (var r = query.executeQuery()) { while (r.next()) rows.add(new Row(new Key(category, r.getString(1)), JSON.readTree(r.getString(2)))); }
        }
        var warnings = new ArrayList<String>();
        boolean partial = !coverage.equals("COMPLETE");
        if (partial) warnings.add("类别覆盖：" + coverage + "；候选关系不代表所有动态来源。");
        for (var row : rows) {
            if (row.data().has("error")) { partial = true; warnings.add("记录采集失败：" + row.key().id() + " · " + row.data().path("error").asText()); }
            if (row.data().path("geometryStatus").asText().equals("METADATA_ONLY") || row.data().path("status").asText().equals("UNSUPPORTED") || row.data().path("truncated").asBoolean()) {
                partial = true; warnings.add("记录有覆盖缺口：" + row.key().id());
            }
        }
        int next = offset + rows.size() < total ? offset + rows.size() : -1;
        if (next >= 0) warnings.add("本页不是全部结果；下一页 offset=" + next);
        Status status = !rows.isEmpty() && rows.stream().allMatch(row -> row.data().has("error")) ? Status.FAILED
                : partial ? Status.PARTIAL : total == 0 ? Status.NOT_FOUND : Status.FOUND;
        RetrievalTelemetry.operation("structured.query", System.nanoTime() - started, 1);
        return new Page(profile, category, status, coverage, total, next, rows, warnings);
    }
    private static void bind(PreparedStatement p, List<?> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) p.setObject(i + 1, values.get(i));
    }
    private void ensureIndex() throws Exception {
        synchronized (INDEX_LOCK) {
            try (var c = db.getConnection()) {
                try (var p = c.prepareStatement("SELECT 1 FROM rag_runtime_snapshot WHERE profile_id=?")) {
                    p.setString(1, profile); try (var rows = p.executeQuery()) { if (!rows.next()) throw new IllegalArgumentException("Snapshot profile not found"); }
                }
                try (var p = c.prepareStatement("SELECT version FROM rag_runtime_query_version WHERE profile_id=?")) {
                    p.setString(1, profile); try (var r = p.executeQuery()) { if (r.next() && r.getInt(1) == INDEX_VERSION) return; }
                }
                coverage("entity_attributes");
                c.setAutoCommit(false);
                long started = System.nanoTime();
                try {
                    for (String table : List.of("rag_runtime_fact","rag_runtime_lookup", "rag_runtime_link", "rag_runtime_query_version"))
                        try (var p = c.prepareStatement("DELETE FROM " + table + " WHERE profile_id=? OR profile_id NOT IN (SELECT profile_id FROM rag_runtime_snapshot)")) { p.setString(1, profile); p.executeUpdate(); }
                    try (var select = c.prepareStatement("SELECT category,record_id,record_data FROM rag_runtime_record WHERE profile_id=? AND category IN (" + String.join(",", INDEXED.stream().sorted().map(value -> "'" + value + "'").toList()) + ") ORDER BY category,record_id");
                         var lookup = c.prepareStatement("INSERT INTO rag_runtime_lookup VALUES(?,?,?,?,?,?,?,?)");
                         var edge = c.prepareStatement("MERGE INTO rag_runtime_link KEY(profile_id,from_category,from_id,relation,to_category,to_id) VALUES(?,?,?,?,?,?)");
                         var fact=c.prepareStatement("MERGE INTO rag_runtime_fact KEY(profile_id,category,field,fact_value,record_id) VALUES(?,?,?,?,?)")) {
                        select.setString(1, profile); int pending = 0;
                        var recipes = new HashMap<String,Row>(); var recipesByType = new HashMap<String,List<Row>>(); var machines = new ArrayList<Row>();
                        try (var records = select.executeQuery()) {
                            while (records.next()) {
                                BuildCancellation.check(); String category = records.getString(1); if (!INDEXED.contains(category)) continue;
                                String id = records.getString(2); var row = JSON.readTree(records.getString(3));
                                if (!row.has("error") && category.equals("recipes")) {
                                    var recipe = new Row(new Key(category,id),row.path("definition")); recipes.put(id,recipe);
                                    recipesByType.computeIfAbsent(row.path("definition").path("type").asText(), ignored -> new ArrayList<>()).add(recipe);
                                }
                                if (!row.has("error") && category.equals("multiblocks")) machines.add(new Row(new Key(category,id),row));
                                String subject = row.path("id").asText(id);
                                if (category.equals("loot_entrypoints")) subject = row.path("source").asText(subject);
                                if (category.equals("trades")) subject = row.path("entity").asText(subject);
                                for(String field:List.of("id","entity","profession","level","station","outputPotionId","inputPotionId","source","lootTable"))if(row.path(field).isValueNode()) {
                                    fact.setString(1,profile);fact.setString(2,category);fact.setString(3,id);fact.setString(4,field);fact.setString(5,row.path(field).asText());fact.addBatch();
                                }
                                var state = row.path("state");
                                var axes=new TreeMap<String,String>();
                                state.path("traits").fields().forEachRemaining(entry->{if(entry.getValue().isValueNode())axes.put(entry.getKey().equals("growthStage")?"growth_stage":entry.getKey(),entry.getValue().asText());});
                                state.path("traits").path("axes").fields().forEachRemaining(entry->{if(entry.getValue().isValueNode())axes.put(entry.getKey(),entry.getValue().asText());});
                                for(var axis:axes.entrySet()){if(!axis.getKey().matches("[a-zA-Z0-9_]{1,40}"))continue;fact.setString(1,profile);fact.setString(2,category);fact.setString(3,id);fact.setString(4,"state."+axis.getKey());fact.setString(5,axis.getValue());fact.addBatch();}
                                lookup.setString(1, profile); lookup.setString(2, category); lookup.setString(3, id); lookup.setString(4, subject);
                                lookup.setString(5, Set.of("entity_state_attributes","entity_icons").contains(category) ? row.has("error") ? row.path("stateKey").asText("default") : EntityAppearanceState.key(row) : "default");
                                String age=state.path("traits").path("age").asText();
                                Boolean baby = null;
                                if (row.has("baby")) baby = row.path("baby").asBoolean();
                                else if (Set.of("baby","adult").contains(age)) baby = age.equals("baby");
                                lookup.setObject(6, baby);
                                lookup.setObject(7, state.path("traits").has("equipment") ? !state.path("traits").path("equipment").isEmpty() : null);
                                lookup.setString(8, state.path("origin").asText("")); lookup.addBatch();
                                for (var link : links(category, id, row)) {
                                    edge.setString(1, profile); edge.setString(2, link.from().category()); edge.setString(3, link.from().id()); edge.setString(4, link.relation()); edge.setString(5, link.to().category()); edge.setString(6, link.to().id()); edge.addBatch();
                                }
                                if (++pending % 200 == 0) { lookup.executeBatch(); lookup.clearBatch(); edge.executeBatch(); edge.clearBatch();fact.executeBatch();fact.clearBatch(); }
                            }
                        }
                        lookup.executeBatch(); edge.executeBatch(); edge.clearBatch();fact.executeBatch();
                        for (var machine : machines) {
                            var candidates = new LinkedHashSet<Row>(recipesByType.getOrDefault(machine.data().path("recipeType").asText(), List.of()));
                            for (var explicit : machine.data().path("recipeIds")) if (recipes.containsKey(explicit.asText())) candidates.add(recipes.get(explicit.asText()));
                            for (var recipe : candidates) if (RuntimeStructures.recipeMatches(machine.data(), recipe.data(), recipe.key().id())) {
                                edge.setString(1, profile); edge.setString(2, "recipes"); edge.setString(3, recipe.key().id()); edge.setString(4, "WORKSTATION"); edge.setString(5, "multiblocks"); edge.setString(6, machine.key().id()); edge.addBatch();
                            }
                            edge.executeBatch(); edge.clearBatch();
                        }
                    }
                    try (var p = c.prepareStatement("INSERT INTO rag_runtime_query_version VALUES(?,?)")) { p.setString(1, profile); p.setInt(2, INDEX_VERSION); p.executeUpdate(); }
                    c.commit();
                    RetrievalTelemetry.operation("structured.index.build", System.nanoTime() - started, 1);
                } catch (Exception failure) { try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); } throw failure; }
            }
        }
    }
    private static List<Link> links(String category, String id, JsonNode row) throws Exception {
        var links = new ArrayList<Link>(); if (row.has("error")) return links; var from = new Key(category, id);
        if (category.equals("loot_entrypoints") || RuntimeAttributes.CATEGORIES.contains(category)) {
            String table = row.path("lootTable").asText();
            if (!table.isBlank()) {
                links.add(new Link(from, "LOOT_TABLE", new Key("loot_tables", table)));
                links.add(new Link(new Key("registries", row.path("source").asText(row.path("id").asText())), category.equals("loot_entrypoints") ? "DECLARED_LOOT" : "SAMPLED_LOOT", new Key("loot_tables", table)));
            }
        }
        if(category.equals("potions"))for(var effect:row.path("effects"))if(effect.path("effect").isTextual())links.add(new Link(from,"POTION_EFFECT",new Key("effects",effect.path("effect").asText())));
        if(category.equals("enchantments")) {
            for(var item:row.path("supportedItems"))if(item.isTextual())links.add(new Link(new Key("registries",item.asText()),"DEFAULT_ENCHANTMENT_APPLICABILITY",from));
            for(var incompatible:row.path("incompatible"))if(incompatible.isTextual())links.add(new Link(from,"INCOMPATIBLE",new Key("enchantments",incompatible.asText())));
        }
        if (category.equals("acquisition_index")) for (var source : row.path("sources"))
            links.add(new Link(from, "ACQUISITION_SOURCE", new Key(source.path("category").asText(), source.path("recordId").asText())));
        if (category.equals("loot_tables") || category.equals("loot_modifiers")) lootLinks(row.path("definition"), from, links);
        if (RuntimeStructures.CATEGORIES.contains(category)) {
            for (var reference : RuntimeStructures.references(row)) links.add(new Link(from, "EXPLICIT_REFERENCE", new Key(reference.category(), reference.id())));
            if (category.equals("multiblocks") && row.has("controller")) links.add(new Link(new Key("registries", row.path("controller").asText()), "CONTROLLER", from));
            if (Set.of("structure_templates", "multiblocks").contains(category)) for (String material : RuntimeStructures.materials(category, row).keySet())
                links.add(new Link(new Key("registries", material), "MATERIAL_DEFAULT_PALETTE", from));
        }
        return links;
    }
    private static void lootLinks(JsonNode node, Key from, List<Link> links) {
        if (node.isArray()) { node.forEach(child -> lootLinks(child, from, links)); return; }
        if (!node.isObject()) return;
        if (Set.of("minecraft:item","item").contains(node.path("type").asText())&&node.path("name").asText().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            links.add(new Link(new Key("registries",node.path("name").asText()),"RULE_OUTPUT_CANDIDATE",from));
        if (Set.of("minecraft:loot_table", "loot_table").contains(node.path("type").asText())) {
            String target = node.path("value").asText(node.path("name").asText());
            if (target.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) links.add(new Link(from, "NESTED_LOOT", new Key("loot_tables", target)));
        }
        for (String field : List.of("pools", "entries", "children", "functions", "value")) if (node.path(field).isContainerNode()) lootLinks(node.path(field), from, links);
    }
    public Links related(Key key, boolean incoming, int offset, int limit) throws Exception {
        return related(key, incoming, null, offset, limit);
    }
    public Links related(Key key, boolean incoming, String relation, int offset, int limit) throws Exception {
        bounds(offset, limit); ensureIndex(); String side = incoming ? "to" : "from";
        String where = " FROM rag_runtime_link WHERE profile_id=? AND " + side + "_category=? AND " + side + "_id=?" + (relation == null ? "" : " AND relation=?");
        var links = new ArrayList<Link>(); int total;
        try (var c = db.getConnection(); var count = c.prepareStatement("SELECT COUNT(*)" + where);
             var query = c.prepareStatement("SELECT from_category,from_id,relation,to_category,to_id" + where + " ORDER BY from_category,from_id,relation,to_category,to_id LIMIT ? OFFSET ?")) {
            var parameters = new ArrayList<String>(List.of(profile, key.category(), key.id())); if (relation != null) parameters.add(relation);
            bind(count, parameters); bind(query, parameters);
            try (var r = count.executeQuery()) { r.next(); total = r.getInt(1); }
            query.setInt(parameters.size()+1, limit); query.setInt(parameters.size()+2, offset);
            try (var r = query.executeQuery()) { while (r.next()) links.add(new Link(new Key(r.getString(1), r.getString(2)), r.getString(3), new Key(r.getString(4), r.getString(5)))); }
        }
        return new Links(links, total, offset + links.size() < total ? offset + links.size() : -1);
    }
    public Expansion expand(Key seed, int maxDepth, int maxRecords) throws Exception {
        return expand(seed,maxDepth,maxRecords,Set.of());
    }
    public Expansion expand(Key seed, int maxDepth, int maxRecords, Set<String> relations) throws Exception {
        Objects.requireNonNull(relations);
        if (relations.size()>8) throw new IllegalArgumentException("Too many relation filters");
        if (maxDepth < 0 || maxDepth > 8 || maxRecords < 1 || maxRecords > 100) throw new IllegalArgumentException("Invalid expansion budget");
        var visited = new LinkedHashSet<Key>(); var queue = new ArrayDeque<Map.Entry<Key,Integer>>(); queue.add(Map.entry(seed, 0));
        var rows = new ArrayList<Row>(); var links = new LinkedHashSet<Link>(); var warnings = new LinkedHashSet<String>(); boolean truncated = false;
        while (!queue.isEmpty()) {
            var current = queue.remove(); if (!visited.add(current.getKey())) continue;
            if (visited.size() > maxRecords) { truncated = true; break; }
            Key key = current.getKey();
            if (INDEXED.contains(key.category())&&!(key.category().equals("registries")&&!key.id().contains("|"))) {
                var page = get(key.category(), key.id()); rows.addAll(page.rows()); warnings.addAll(page.warnings());
                if (page.rows().isEmpty()) warnings.add("关联目标没有可读记录：" + key.category() + "/" + key.id() + "；状态 " + page.status());
            }
            var selectedEdges = new ArrayList<Link>();
            var filters = new ArrayList<String>(relations.stream().sorted().toList()); if (filters.isEmpty()) filters.add(null);
            for (String relation : filters) {
                var edges = related(key, false, relation, 0, 100);
                selectedEdges.addAll(edges.links()); if (edges.nextOffset() >= 0) truncated = true;
            }
            for (var edge : selectedEdges) {
                links.add(edge);
                if (edge.relation().equals("SAMPLED_LOOT")) warnings.add("存在构造或捕获样本的掉落入口关系；采样入口不能当作所有状态的最终掉落保证。");
                if (visited.contains(edge.to())) continue;
                if (current.getValue() >= maxDepth) { truncated = true; continue; }
                queue.add(Map.entry(edge.to(), current.getValue() + 1));
            }
        }
        if (truncated) warnings.add("关联遍历达到深度、记录或分页上限；结果不是完整关系全集。");
        return new Expansion(rows, new ArrayList<>(links), truncated, new ArrayList<>(warnings));
    }
    public record Pool(int ordinal, JsonNode rule, JsonNode tableConditions, JsonNode tableFunctions) {}
    public record Pools(Page table, int total, int nextOffset, List<Pool> pools) {
        public Pools { pools = List.copyOf(pools); }
    }
    public Pools lootPools(String tableId, int offset, int limit) throws Exception {
        bounds(offset, limit); var table = get("loot_tables", tableId); var pools = new ArrayList<Pool>();
        if (table.rows().isEmpty() || table.rows().getFirst().data().has("error")) return new Pools(table, 0, -1, pools);
        var definition = table.rows().getFirst().data().path("definition"); var raw = definition.path("pools");
        for (int i = offset; i < Math.min(raw.size(), offset + limit); i++)
            pools.add(new Pool(i + 1, raw.get(i), definition.path("conditions"), definition.path("functions")));
        return new Pools(table, raw.size(), offset + pools.size() < raw.size() ? offset + pools.size() : -1, pools);
    }
    public List<Map.Entry<String,String>> translations(String language,String term,int offset,int limit)throws Exception {
        bounds(offset,limit);var page=get("language",language);if(page.rows().isEmpty())return List.of();
        var values=new TreeMap<String,String>();page.rows().getFirst().data().path("translations").fields().forEachRemaining(entry->{if(term==null||entry.getKey().contains(term)||entry.getValue().asText().contains(term))values.put(entry.getKey(),entry.getValue().asText());});
        return values.entrySet().stream().skip(offset).limit(limit).map(entry->Map.entry(entry.getKey(),entry.getValue())).toList();
    }
    public Expansion brewingChain(String rule)throws Exception {
        var selected=get("brewing",rule);if(selected.rows().isEmpty())return new Expansion(List.of(),List.of(),false,selected.warnings());
        var all=JSON.createObjectNode();var records=all.putArray("records");int offset=0;
        do {var page=page("brewing",null,State.any(),offset,100);page.rows().forEach(row->records.add(row.data()));offset=page.nextOffset();}while(offset>=0);
        String chain=new BrewingChains(all).prerequisites(selected.rows().getFirst().data());
        return new Expansion(selected.rows(),List.of(),chain.contains("达到64步"),List.of(chain));
    }
    public List<VectorStore.SearchResult> evidencePages(Row row) {
        var pages=new ArrayList<VectorStore.SearchResult>();sections(row,row.data(),"",pages);return List.copyOf(pages);
    }
    private void sections(Row row,JsonNode value,String pointer,List<VectorStore.SearchResult> pages) {
        String raw=value.toPrettyString();
        if(raw.length()<=12000){pages.add(evidence(row,"JSON section "+(pointer.isEmpty()?"/":pointer)+"\n"+raw));return;}
        if(value.isObject())value.fields().forEachRemaining(entry->sections(row,entry.getValue(),pointer+"/"+entry.getKey().replace("~","~0").replace("/","~1"),pages));
        else if(value.isArray())for(int i=0;i<value.size();i++)sections(row,value.get(i),pointer+"/"+i,pages);
        else pages.add(evidence(row,"字段超过单次证据预算，保留在原始记录中；未输出残缺文本。JSON pointer："+pointer));
    }
    public VectorStore.SearchResult evidence(Row row, String text) {
        String path = "mkb-runtime:" + profile + "/" + (row.key().category().equals("recipes") ? "recipe" : row.key().category()) + "/" + row.key().id();
        if (text.length() > 16000) text = "记录超过单次证据预算；未输出残缺规则。请按池、状态或条件分页查询。记录："+row.key().category()+"/"+row.key().id();
        String identity;try{identity=environment().toString();}catch(java.sql.SQLException|java.io.IOException failure){throw new IllegalStateException("Snapshot identity unavailable",failure);}
        text = "结构化查询；快照环境 " + identity + "；类别 " + row.key().category() + "；记录 " + row.key().id() + "\n" + text;
        return new VectorStore.SearchResult("structured/" + BuildRequest.fingerprint(path + "\n" + text), text,
                RuntimeRules.namespace(row.data().path("id").asText(row.key().id())), row.data().path("id").asText(row.key().id()), "runtime_" + (row.key().category().equals("recipes") ? "recipe" : row.key().category()), 1, path);
    }
}
