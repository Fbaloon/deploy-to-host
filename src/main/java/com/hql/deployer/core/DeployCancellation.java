package com.hql.deployer.core;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消信号。
 *
 * <p>用一个可共享的对象把「用户点了停止」这件事传遍流水线：Run 工具窗的停止按钮置位它，
 * 各阶段的执行体在自己的循环里轮询它并尽快收手。相比布尔参数，它可以被自由传递，
 * 不必逐层加方法参数。</p>
 *
 * <p>置位之后不可复位——同一次执行中即使某个阶段正常结束，也不应该让取消信号失效，
 * 否则「停止」后又会在下一阶段继续跑。</p>
 *
 * @author hql on 2026/9/28
 */
public final class DeployCancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * 请求取消。可从任意线程调用，重复调用安全。
     */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 已取消则抛出 {@link DeployCancelledException}，供长循环在每轮开头调用。
     *
     * @param stage 当前阶段，用于日志定位
     */
    public void throwIfCancelled(@NotNull DeployStage stage) {
        if (cancelled.get()) {
            throw new DeployCancelledException(stage);
        }
    }
}
