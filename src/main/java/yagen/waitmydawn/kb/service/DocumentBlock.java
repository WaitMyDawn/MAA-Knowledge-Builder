package yagen.waitmydawn.kb.service;

import java.util.List;

/** 页码仅来自确定的分页来源；Word 的自动排版页码保持未知。 */
record DocumentBlock(String text, String heading, Integer page, String method, List<Picture> pictures,
                     String headingPath,String lineage,String kind,String tableId) {
    DocumentBlock(String text,String heading,Integer page,String method,List<Picture> pictures) {
        this(text,heading,page,method,pictures,heading,"/root/legacy-"+yagen.waitmydawn.kb.model.BuildRequest.fingerprint(heading).substring(0,12)+"/",method.equals("TABLE")?"TABLE_ROW":"PARAGRAPH",null);
    }
    record Picture(byte[] bytes, String caption, String position, String bounds, String role) {}
    interface Sink { void accept(DocumentBlock block) throws Exception; }
}
