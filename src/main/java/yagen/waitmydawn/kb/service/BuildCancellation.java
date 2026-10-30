package yagen.waitmydawn.kb.service;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** 构建使用协作取消，避免中断 H2 文件 I/O；下载子任务另有局部停止信号。 */
final class BuildCancellation {
    private static final ThreadLocal<BooleanSupplier> LOCAL=new ThreadLocal<>();
    static void check() {
        var task=BuildTaskJournal.current();var local=LOCAL.get();
        if(Thread.currentThread().isInterrupted()||task!=null&&task.cancelRequested()||local!=null&&local.getAsBoolean())throw new CancellationException("Build operation cancelled");
    }
    static Runnable carry(BooleanSupplier stop,Runnable work) {
        return ()->{var previous=LOCAL.get();LOCAL.set(stop);try{work.run();}finally{if(previous==null)LOCAL.remove();else LOCAL.set(previous);}};
    }
}
