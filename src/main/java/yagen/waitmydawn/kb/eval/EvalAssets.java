package yagen.waitmydawn.kb.eval;

import yagen.waitmydawn.kb.model.AnswerArtifacts;
import yagen.waitmydawn.kb.service.*;
import java.nio.file.*;
import java.util.*;

/** 评测快照按内容身份归档图片，使校验与历史回放使用同一独立资源目录。 */
final class EvalAssets {
    private EvalAssets() {}
    static List<String> archive(AnswerArtifacts artifacts,Path sourceRoot,Path runRoot) {
        var errors=new ArrayList<String>();var copied=new HashSet<String>();var destination=new ImageAssetStore(runRoot);
        if(artifacts==null) return List.of();
        for(var picture:artifacts.pictures()) {
            if(!copied.add(picture.assetId())) continue;
            try {
                Path source=AnswerMedia.resolveImage(sourceRoot,picture);
                if(Files.size(source)>32L*1024*1024) throw new java.io.IOException("图片超过归档上限");
                var saved=destination.restore(picture.assetId(),Files.readAllBytes(source));
                if(!saved.relativePath().equals(picture.relativePath())) throw new java.io.IOException("图片身份与路径不一致");
                AnswerMedia.resolveImage(runRoot,picture);
            } catch(java.io.IOException | IllegalArgumentException e) {errors.add(picture.id()+": "+e.getMessage());}
        }
        return List.copyOf(errors);
    }
}
