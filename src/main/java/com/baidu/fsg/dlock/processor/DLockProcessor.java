/*
 * Copyright (c) 2017 Baidu, Inc. All Rights Reserve.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.baidu.fsg.dlock.processor;

import com.baidu.fsg.dlock.domain.DLockConfig;
import com.baidu.fsg.dlock.domain.DLockEntity;
import com.baidu.fsg.dlock.domain.DLockStatus;
import com.baidu.fsg.dlock.exception.OptimisticLockingException;
import com.baidu.fsg.dlock.exception.RedisProcessException;

/**
 * 对redis的client进行了进一步的封装
 *
 * @author chenguoqing
 */
public interface DLockProcessor {

    /**
     * 获取redis的值,通过唯一的key
     *
     * @param uniqueKey redis key
     * @return {@link DLockEntity} value存在对象中的 {@link DLockEntity#locker} 字段,如果value不为null, 那么{@link DLockEntity#lockStatus}  = {@link DLockStatus#PROCESSING};
     * @throws RedisProcessException 操作redis发生异常
     */
    DLockEntity load(String uniqueKey);

    /**
     * The method implements the "lock" syntax<br>
     * <li>DB</li>
     * The implementations should update the (lockStatus,locker,lockTime) with
     * DB record lock under the condition (lockStatus=0)<p>
     * <p>
     * <li>Redis</li>
     * The implementations should set unique key, value(locker), and expire time
     *
     * @param newLock
     * @param lockConfig
     * @throw OptimisticLockingFailureException
     */
    /**
     * 尝试加锁 利用redis  set(final String key, final String value,NX, PX,final long time)方法
     *
     * @param newLock    包含这个key的value
     * @param lockConfig 包含redis的key和过期时间
     * @return 加锁成功返回true, 加锁失败返回false
     * @throws RedisProcessException 操作redis发生异常
     */
    boolean updateForLock(DLockEntity newLock, DLockConfig lockConfig);

    /**
     * The method implements the "lock" syntax with existing expire lock.<br>
     * <li>DB</li>
     * The implementations should update
     * (lockStatus,locker,lockTime) with DB line lock under the condition (lockStatus=1 && locker==expireLock.locker)<p>
     * <p>
     * <li>Redis</li>
     * The implementation is unsupported because of the Redis expire mechanism.
     *
     * @param expireLock
     * @param dbLock
     * @param lockConfig
     */
    void updateForLockWithExpire(DLockEntity expireLock, DLockEntity dbLock, DLockConfig lockConfig);

    /**
     * 利用lua脚本延长redis key的过期时间
     *
     * @param newLeaseLock 包含这个key期望的value
     * @param lockConfig   包含redis的key和期望延长到的过期时间
     * @throws RedisProcessException      操作redis发生异常
     * @throws OptimisticLockingException 锁被其他线程持有,期望的value的真实的value不相同
     */
    void expandLockExpire(DLockEntity newLeaseLock, DLockConfig lockConfig);

    /**
     * 删除redis的key
     *
     * @param currentLock 当前锁的val
     * @param lockConfig  当前的redis key
     * @throws RedisProcessException 操作redis发生异常
     * @throws OptimisticLockingException 当前锁被其他的线程所持有
     */
    void updateForUnlock(DLockEntity currentLock, DLockConfig lockConfig);

    /**
     * 检测这个key是不是已经不存在于redis中
     *
     * @param uniqueKey redis key
     * @return 如果不存在 返回true,如果存在返回false
     */
    boolean isLockFree(String uniqueKey);

}
