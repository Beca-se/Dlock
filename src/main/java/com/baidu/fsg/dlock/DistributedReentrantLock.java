package com.baidu.fsg.dlock;

import com.baidu.fsg.dlock.domain.DLockConfig;
import com.baidu.fsg.dlock.domain.DLockEntity;
import com.baidu.fsg.dlock.domain.DLockStatus;
import com.baidu.fsg.dlock.exception.DLockProcessException;
import com.baidu.fsg.dlock.exception.OptimisticLockingException;
import com.baidu.fsg.dlock.processor.DLockProcessor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;


/**
 * 分布式锁的最核心类,实现了lock锁,可重入
 *
 * @author zhouhu
 */
@Slf4j
@SuppressWarnings("ALL")
public class DistributedReentrantLock implements Lock {

    /**
     * 构造函数
     *
     * @param lockConfig    {@link DLockConfig}
     * @param lockProcessor {@link DLockProcessor}
     */
    public DistributedReentrantLock(DLockConfig lockConfig, DLockProcessor lockProcessor) {
        this.lockConfig = lockConfig;
        this.lockProcessor = lockProcessor;
    }

    /**
     * 将锁的value对象保持在线程局部变量中
     */
    private final ThreadLocal<DLockEntity> lockEntityThreadLocal = new ThreadLocal<>();
    /**
     * 锁的初始配置
     */
    private final DLockConfig lockConfig;
    /**
     * 操作redis的类
     */
    private final DLockProcessor lockProcessor;

    /**
     * 等待队列的头，延迟初始化。除了初始化之外，它只通过setHead方法进行修改
     */
    private final AtomicReference<Node> head = new AtomicReference<>();
    /**
     * 等待队列的尾部，延迟初始化。仅通过方法enq修改以添加新的等待节点。
     */
    private final AtomicReference<Node> tail = new AtomicReference<>();

    /**
     * 持有当前锁的线程
     */
    private final AtomicReference<Thread> exclusiveOwnerThread = new AtomicReference<>();
    /**
     * 重试线程
     */
    private final AtomicReference<RetryBaseLockThread> retryLockRef = new AtomicReference<>();
    /**
     * 续约线程
     */
    private final AtomicReference<ExpandBaseLockLeaseThread> expandLockRef = new AtomicReference<>();

    /**
     * 锁被占用了几次
     */
    private final AtomicInteger holdCount = new AtomicInteger(0);


    @Override
    public Condition newCondition() {
        throw new UnsupportedOperationException("不支持这个操作");
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        throw new UnsupportedOperationException("不支持这个操作");
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        throw new UnsupportedOperationException("不支持这个操作");
    }

    @Override
    public void lock() {
        // lock db record
        if (!tryLock()) {
            acquireQueued(addWaiter());
        }
    }

    /**
     * 尝试加锁,只会尝试一次
     */
    @Override
    public boolean tryLock() {

        // current thread can reentrant, and locked times add once
        // 如果锁被当前线程持有,将持有次数加1
        if (Thread.currentThread() == this.exclusiveOwnerThread.get()) {
            log.info("重入,加锁成功");
            // 持有次数+1
            this.holdCount.incrementAndGet();
            // 返回加锁成功
            return true;
        }
        // 构建加锁实体
        DLockEntity newLock = new DLockEntity();
        newLock.setLockTime(System.currentTimeMillis());
        newLock.setLocker(generateLocker());
        newLock.setLockStatus(DLockStatus.PROCESSING);
        // 设置加锁状态为false
        boolean locked = false;
        try {
            // 尝试加锁,并接受加锁结果
            log.info("加锁的key是{}.value是{}.", lockConfig.getLockUniqueKey(), newLock);
            locked = lockProcessor.updateForLock(newLock, lockConfig);

        } catch (DLockProcessException e) {
            log.error("操作redis发生异常,原因是{}.", e);
        }
        // 如果加锁成功
        if (locked) {
            // 将value存入线程局部变量
            lockEntityThreadLocal.set(newLock);
            // 设置锁被当前线程占有
            this.exclusiveOwnerThread.set(Thread.currentThread());

            // 占有次数设置为1
            this.holdCount.set(1);
            // 关闭重试线程
            shutdownRetryThread();

            // 开启续约线程
            startExpandLockLeaseThread(newLock);
        }
        // 返回加锁结果
        return locked;
    }

    /**
     * Attempts to release this lock.<p>
     * <p>
     * If the current thread is the holder of this lock then the hold
     * count is decremented.  If the hold count is now zero then the lock
     * is released.  If the current thread is not the holder of this
     * lock then {@link IllegalMonitorStateException} is thrown.
     *
     * @throws IllegalMonitorStateException if the current thread does not
     *                                      hold this lock
     */
    @Override
    public void unlock() throws IllegalMonitorStateException {
        // lock must be hold by current thread
        log.error("你要删除的key是{},当前锁被线程{}持有,想要删除锁的线程是{}.", lockConfig.getLockUniqueKey(), this.exclusiveOwnerThread.get(), Thread.currentThread().getName());
        if (Thread.currentThread() != this.exclusiveOwnerThread.get()) {
            throw new IllegalMonitorStateException();
        }

        // lock is still be hold
        if (holdCount.decrementAndGet() > 0) {
            return;
        }

        // clear remote lock
        DLockEntity currentLock = lockEntityThreadLocal.get();
        log.info("当前锁的value为{}.", currentLock);
        try {
            // release remote lock
            lockProcessor.updateForUnlock(currentLock, lockConfig);

        } catch (OptimisticLockingException | DLockProcessException e) {
            // NOPE. Lock will deleted automatic after the expire time.

        } finally {
            // 将占有锁的线程替换为null
            this.exclusiveOwnerThread.compareAndSet(Thread.currentThread(), null);
            // 删除线程局部变量
            lockEntityThreadLocal.remove();
            // 关闭续约线程
            shutdownExpandThread();

            // 唤醒等待对象竞争
            unparkQueuedNode();
        }
    }

    /**
     * 尝试加锁未成功,添加到等待队列
     *
     * @return 当前节点的node
     */
    private Node addWaiter() {
        Node node = new Node(Thread.currentThread());
        // Try the fast path of enq; backup to full enq on failure
        Node prev = tail.get();
        // 将当前节点添加到尾部节点
        if (prev != null) {
            node.prev.set(prev);
            if (tail.compareAndSet(prev, node)) {
                prev.next.set(node);
                return node;
            }
        }
        // 初始化CLH队列
        enq(node);
        // 返回当前节点的node
        return node;
    }

    /**
     * 初始化CLH链表
     *
     * @param node 当前节点
     */
    private void enq(final Node node) {
        for (; ; ) {
            // 获取尾部节点
            Node t = tail.get();
            // 如果没有尾部节点,进行初始化
            if (t == null) {
                // 创建一个空节点
                Node h = new Node();
                h.next.set(node);
                node.prev.set(h);
                // 将CLH的头节点设置为空节点
                if (head.compareAndSet(null, h)) {
                    // 将尾节点设置为第一个节点
                    tail.set(node);
                    break;
                }
            } else {
                // 如果存在尾部节点,将当前节点设置为尾部节点
                node.prev.set(t);
                if (tail.compareAndSet(t, node)) {
                    t.next.set(node);
                    break;
                }
            }
        }
    }

    /**
     * 自旋获得锁
     *
     * @param node 当前要获得锁的node
     */
    private void acquireQueued(final Node node) {
        for (; ; ) {
            // 如果当前节点是head的下一个节点,并且加锁成功
            final Node p = node.prev.get();
            if (p == head.get() && tryLock()) {
                // 将头节点设置为自己
                head.set(node);
                // 将上一个链表设置为无用对象,帮助GC回收
                p.next.set(null);
                node.prev.set(null);
                break;
            }

            // 如果上一个节点不是头节点,或者尝试加锁失败,检查是否需要开启重试线程 如果锁没有被占用,开启重试线程,重试线程会去唤醒等待队列竞争锁
            if (exclusiveOwnerThread.get() == null) {
                startRetryThread();
            }

            // 将当前线程暂停,等待获得锁
            LockSupport.park(this);
        }
    }

    /**
     * 唤醒等待队列去获得锁
     */
    private void unparkQueuedNode() {
        // 唤醒头部线程的下一个去获得锁
        Node h = head.get();
        if (h != null && h.next.get() != null) {
            LockSupport.unpark(h.next.get().t);
        }
    }

    /**
     * 生成redis value
     */
    private String generateLocker() {
        return Thread.currentThread().getName() + "-" + Thread.currentThread().getId();
    }


    /**
     * 开启续约线程
     *
     * @param lock redis 的value
     */
    private void startExpandLockLeaseThread(DLockEntity lock) {
        ExpandBaseLockLeaseThread t = expandLockRef.get();

        int retryInterval = (int) (lockConfig.getMillisLease() * 0.75);
        while (t == null || t.getState() == Thread.State.TERMINATED) {
            // set new expand lock thread
            expandLockRef.compareAndSet(t, new ExpandBaseLockLeaseThread(lock, retryInterval));

            // retrieve the new expand thread instance
            t = expandLockRef.get();
        }

        if (t.startState.compareAndSet(0, 1)) {
            log.info("开始续约线程,续约的key是{},时间间隔是{}毫秒.", lockConfig.getLockUniqueKey(), retryInterval);
            t.start();
        }
    }

    /**
     * 关闭续约线程
     */
    private void shutdownExpandThread() {
        ExpandBaseLockLeaseThread t = expandLockRef.get();
        if (t != null && t.isAlive()) {
            t.shouldShutdown.set(true);
        }
    }


    /**
     * 开启重试线程
     */
    private void startRetryThread() {
        RetryBaseLockThread t = retryLockRef.get();

        while (t == null || t.getState() == Thread.State.TERMINATED) {
            retryLockRef.compareAndSet(t, new RetryBaseLockThread((int) (lockConfig.getMillisLease() / 6)));

            t = retryLockRef.get();
        }

        if (t.startState.compareAndSet(0, 1)) {
            log.info("开始重试线程");
            t.start();
        }
    }

    /**
     * 关闭重试线程
     */
    private void shutdownRetryThread() {
        RetryBaseLockThread t = retryLockRef.get();
        if (t != null && t.isAlive()) {
            t.shouldShutdown.set(true);
        }
    }

    /**
     * 重试线程和续约线程的父类
     */
    abstract class BaseLockThread extends Thread {
        /**
         * 锁对象
         */
        final Object sync = new Object();
        /**
         * 重试间隔
         */
        final int retryInterval;
        /**
         * 已经运行
         * 开始状态, 0==> 未开始运行, 1==>代表
         */
        final AtomicInteger startState = new AtomicInteger(0);
        /**
         * 控制是否推出循环的变量
         */
        volatile AtomicBoolean shouldShutdown = new AtomicBoolean(false);

        BaseLockThread(String name, int retryInterval) {
            setDaemon(true);

            this.retryInterval = retryInterval;
            setName(name + "-" + getId());
        }

        @Override
        public void run() {
            while (!shouldShutdown.get()) {
                synchronized (sync) {
                    try {
                        // wait for interval
                        sync.wait(retryInterval);

                        // execute task
                        execute();

                    } catch (InterruptedException e) {
                        shouldShutdown.set(true);
                    }
                }
            }

            // clear associated resources for implementations
            beforeShutdown();
        }

        /**
         * 真正要运行的逻辑
         *
         * @throws InterruptedException 抛出打断异常则说明逻辑出错
         */
        abstract void execute() throws InterruptedException;

        /**
         * 在线程运行完成之前,要进行的操作
         */
        void beforeShutdown() {
        }
    }


    /**
     * 延长租约的线程
     */
    private class ExpandBaseLockLeaseThread extends BaseLockThread {

        final DLockEntity lock;

        ExpandBaseLockLeaseThread(DLockEntity lock, int retryInterval) {
            super("ExpandBaseLockLeaseThread", retryInterval);
            this.lock = lock;
        }

        @Override
        void execute() throws InterruptedException {
            try {
                // 设置加锁时间为当前毫秒数
                lock.setLockTime(System.currentTimeMillis());
                log.info("开始续约,续约的key是{},续约的value是{},续约的过期时间是{}毫秒.",
                        lockConfig.getLockUniqueKey(), lock.getLocker(), lockConfig.getMillisLease());
                // update lock
                lockProcessor.expandLockExpire(lock, lockConfig);

            } catch (OptimisticLockingException e) {
                log.error("续约失败,原因是锁住的value和传入的value不一致,锁不被你持有,或者锁被其他线程持有! 停止续约");
                throw new InterruptedException("锁已被释放");

            } catch (DLockProcessException e) {
                log.warn("续约失败,原因是{}.", e);
                // retry
            }
            log.info("续约成功!");
        }

        @Override
        void beforeShutdown() {
            //将续约线程替换为null
            expandLockRef.compareAndSet(this, null);
        }
    }

    /**
     * 重试线程,重试线程开启条件
     * 1. 锁没有被持有
     * 2. 有等待获取锁队列
     * 如果锁被线程占有, 就没有必要开启重试线程
     */
    private class RetryBaseLockThread extends BaseLockThread {


        RetryBaseLockThread(int retryInterval) {
            super("RetryBaseLockThread-" + lockConfig.getLockUniqueKey(), retryInterval);
        }

        @Override
        void execute() throws InterruptedException {

            // 如果锁被线程持有,结束当前线程
            if (exclusiveOwnerThread.get() != null) {
                throw new InterruptedException("Has running thread.");
            }
            // 获取头节点==> 即上一次持有锁的节点,或者为空头节点
            Node h = head.get();

            // 上一次没有线程持有锁,结束重试线程
            if (h == null) {
                throw new InterruptedException("No waiting thread.");
            }
            // 是否需要重新竞争
            boolean needRetry;
            try {
                // 检查当前锁是否空闲
                needRetry = lockProcessor.isLockFree(lockConfig.getLockUniqueKey());
            } catch (DLockProcessException e) {
                needRetry = true;
            }

            // 如果锁不存在
            if (needRetry) {
                // 唤醒等待队列去加锁
                unparkQueuedNode();
            }
        }

        @Override
        void beforeShutdown() {
            retryLockRef.compareAndSet(this, null);
        }
    }

    /**
     * CLH Queue Node for holds all parked thread
     */
    static class Node {
        final AtomicReference<Node> prev = new AtomicReference<>();
        final AtomicReference<Node> next = new AtomicReference<>();
        final Thread t;

        Node() {
            this(null);
        }

        Node(Thread t) {
            this.t = t;
        }
    }
}
