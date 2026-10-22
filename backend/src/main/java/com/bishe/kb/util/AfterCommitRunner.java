package com.bishe.kb.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把「不受数据库事务管辖的副作用」推迟到事务提交之后执行。
 *
 * <p>向量库落盘、删磁盘原文、刷外部缓存都不是事务资源：一旦做在提交之前，事务回滚就留下
 * 「库里的记录还在、配套的向量与原文却已经没了」的破窗状态——比多留一份待清理的垃圾严重得多。
 *
 * <p>判断依据用 {@code isActualTransactionActive()} 而不是 {@code isSynchronizationActive()}：
 * MyBatis 在<b>无事务</b>的批量操作里也会临时激活一个 synchronization（日志里那句
 * “Transaction not enabled” 就是这么来的），此时注册 afterCommit 永远不会被触发，
 * 副作用会被静默丢掉。只认真正存在的事务边界，没事务就当场执行，语义退化成「按顺序做完」。
 */
public final class AfterCommitRunner {

    private AfterCommitRunner() {
    }

    /**
     * 有事务：提交后执行，回滚则完全不执行；无事务：立即执行。
     *
     * <p>动作本身要能容忍失败（外部副作用已经不在事务保护范围内），异常照常向上抛，
     * 由调用方决定是否降级——本类不静默吞异常。
     */
    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
