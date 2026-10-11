package yagen.waitmydawn.kb.model;

import java.util.List;

/** 分类路径与成员归属独立于兼容用的平面子页面映射。 */
public record WebCatalog(List<Node> nodes, List<Member> members) {
    public record Node(String id, String parentId, String modId, String categoryUrl,
                       int topCategory, String name, List<String> path) {}
    public record Member(String categoryId, String url, String label, String chineseName,
                         String englishName, String role) {}
}
