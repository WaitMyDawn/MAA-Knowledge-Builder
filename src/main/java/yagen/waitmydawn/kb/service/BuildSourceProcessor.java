package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.BuildRequest;
import yagen.waitmydawn.kb.model.DatabaseBuilder;

/** 来源处理器写入调用方提供的上下文；构建的暂存和事务发布由编排器管理。 */
public interface BuildSourceProcessor {
    boolean supports(BuildRequest.Kind kind);
    void process(BuildRequest.Source source, Context context) throws Exception;

    record Context(DatabaseBuilder database, KnowledgeContentStore content, ImageAssetStore images,
                   VectorStore vectors, EmbeddingService embedder, KnowledgeBuilder.ProgressCallback progress,
                   KnowledgeBuilder.BuildResult result,boolean refresh,boolean reparse,java.util.Set<String> visitedDocuments) {
        public Context(DatabaseBuilder db,KnowledgeContentStore content,ImageAssetStore images,VectorStore vectors,EmbeddingService embedder,KnowledgeBuilder.ProgressCallback progress,KnowledgeBuilder.BuildResult result){this(db,content,images,vectors,embedder,progress,result,false,false,new java.util.HashSet<>());}
        public void checkCancelled() {
            BuildCancellation.check();
        }
    }
}
