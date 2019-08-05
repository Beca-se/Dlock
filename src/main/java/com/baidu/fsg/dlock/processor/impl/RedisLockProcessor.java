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
package com.baidu.fsg.dlock.processor.impl;

import com.baidu.fsg.dlock.domain.DLockConfig;
import com.baidu.fsg.dlock.domain.DLockEntity;
import com.baidu.fsg.dlock.domain.DLockStatus;
import com.baidu.fsg.dlock.exception.OptimisticLockingException;
import com.baidu.fsg.dlock.exception.RedisProcessException;
import com.baidu.fsg.dlock.jedis.JedisClient;
import com.baidu.fsg.dlock.processor.DLockProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import java.util.Arrays;

/**
 * The implement of {@link DLockProcessor}. Command set(with NX & PX) & Lua script is used for atomic operations.
 * Redis version must be greater than 2.6.12<p>
 * <p>
 * DataModel:<br>
 * Key: LockUniqueKey, Value: Locker(IP + ThreadID), Expire: lease duration(ms).
 *
 * @author yutianbao
 */
public class RedisLockProcessor implements DLockProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisLockProcessor.class);

    /**
     * Redis command & result code constant
     */
    private static final String SET_ARG_NOT_EXIST = "NX";
    private static final String SET_ARG_EXPIRE = "PX";
    private static final String RES_OK = "OK";

    private final JedisClient jedisClient;

    public RedisLockProcessor(JedisClient jedisClient) {
        this.jedisClient = jedisClient;
    }

    @Override
    public DLockEntity load(String uniqueKey) throws RedisProcessException {
        // GET command
        String locker;
        try {
            locker = jedisClient.get(uniqueKey);
        } catch (Exception e) {
            LOGGER.warn("Exception occurred by GET command for key:" + uniqueKey, e);
            throw new RedisProcessException("Exception occurred by GET command for key:" + uniqueKey, e);
        }
        // build entity
        DLockEntity lockEntity = new DLockEntity();
        lockEntity.setLocker(locker);
        lockEntity.setLockStatus(DLockStatus.PROCESSING);

        if (locker == null) {
            lockEntity.setLockStatus(DLockStatus.INITIAL);
        }
        return lockEntity;
    }

    @Override
    public boolean updateForLock(DLockEntity newLock, DLockConfig lockConfig)
            throws RedisProcessException {
        // SET(NX, PX) command
        String lockRes;
        try {
            lockRes = jedisClient.set(lockConfig.getLockUniqueKey(), newLock.getLocker(), SET_ARG_NOT_EXIST,
                    SET_ARG_EXPIRE, lockConfig.getMillisLease());

        } catch (Exception e) {
            LOGGER.warn("Exception occurred by SET(NX, PX) command for key:" + lockConfig.getLockUniqueKey(), e);
            throw new RedisProcessException(
                    "Exception occurred by SET(NX, PX) command for key:" + lockConfig.getLockUniqueKey(), e);
        }

        return RES_OK.equals(lockRes);
    }

    /**
     * The redis expire mechanism guaranteed the expired key is removed automatic.
     * It is not necessary to check condition(status=1 && expire=true)
     */
    @Override
    public void updateForLockWithExpire(DLockEntity expireLock, DLockEntity dbLock, DLockConfig lockConfig) {
        throw new UnsupportedOperationException("updateForLockWithExpire is not supported");
    }

    @Override
    public void expandLockExpire(DLockEntity leaseLock, DLockConfig lockConfig)
            throws RedisProcessException, OptimisticLockingException {
        // Expire if key is existed and equal with the specified value(locker).
        String leaseScript = "if (redis.call('get', KEYS[1]) == ARGV[1]) then "
                + "    return redis.call('pexpire', KEYS[1], ARGV[2]); "
                + "else"
                + "    return nil; "
                + "end; ";

        Object leaseRes;
        try {
            leaseRes = jedisClient.eval(leaseScript, Arrays.asList(lockConfig.getLockUniqueKey()),
                    Arrays.asList(leaseLock.getLocker(), lockConfig.getMillisLease() + ""));
        } catch (Exception e) {
            LOGGER.warn("Exception occurred by ExpandLease lua script for key:" + lockConfig.getLockUniqueKey(), e);
            throw new RedisProcessException(
                    "Exception occurred by ExpandLease lua script for key:" + lockConfig.getLockUniqueKey(), e);
        }

        // null means lua return nil (the lock is released or be hold by the other request)
        if (leaseRes == null) {
            LOGGER.warn("Fail to lease for key:{} ,locker={}", lockConfig.getLockUniqueKey(), leaseLock.getLocker());
            throw new OptimisticLockingException(
                    "Fail to lease for key:" + lockConfig.getLockUniqueKey() + " ,locker=" + leaseLock.getLocker());
        }
    }

    /**
     * Release lock using lua script.
     *
     * @param currentLock with locker in it
     * @param lockConfig
     * @throws RedisProcessException      if catch any exception from {@link redis.clients.jedis.Jedis}
     * @throws OptimisticLockingException if the lock is released or be hold by another one.
     */
    @Override
    public void updateForUnlock(DLockEntity currentLock, DLockConfig lockConfig)
            throws RedisProcessException, OptimisticLockingException {
        // Delete if key is existed and equal with the specified value(locker).
        String unlockScript = "if (redis.call('get', KEYS[1]) == ARGV[1]) then "
                + "    return redis.call('del', KEYS[1]); "
                + "else "
                + "    return nil; "
                + "end;";

        Object unlockRes;
        try {
            unlockRes = jedisClient.eval(unlockScript, Arrays.asList(lockConfig.getLockUniqueKey()),
                    Arrays.asList(currentLock.getLocker()));
        } catch (Exception e) {
            LOGGER.warn("Exception occurred by Unlock lua script for key:" + lockConfig.getLockUniqueKey(), e);
            throw new RedisProcessException(
                    "Exception occurred by Unlock lua script for key:" + lockConfig.getLockUniqueKey(), e);
        }

        // null means lua return nil (the lock is released or be hold by the other request)
        if (unlockRes == null) {
            LOGGER.warn("Fail to unlock for key:{} ,locker={}", lockConfig.getLockUniqueKey(), currentLock.getLocker());
            throw new OptimisticLockingException("Fail to unlock for key:" + lockConfig.getLockUniqueKey()
                    + ",locker=" + currentLock.getLocker());
        }
    }

    @Override
    public boolean isLockFree(String uniqueKey) {
        DLockEntity locked = this.load(uniqueKey);
        return locked.getLockStatus() == DLockStatus.INITIAL;
    }

}
