package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.BuildRequest;
import yagen.waitmydawn.kb.model.DatabaseBuilder;

/** 来源处理器仅写入暂存数据库；不得自行发布或清空基础库。 */
public interface BuildSourceProcessor {
    boolean supports(BuildRequest.Kind kind);
    void process(BuildRequest.Source source, Context context) throws Exception;

    record Context(DatabaseBuilder database, KnowledgeContentStore content, ImageAssetStore images,
                   VectorStore vectors, EmbeddingService embedder, KnowledgeBuilder.ProgressCallback progress,
                   KnowledgeBuilder.BuildResult result) {
        public void checkCancelled() {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Knowledge build cancelled");
        }
    }
}
