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
package com.baidu.fsg.dlock.domain;

import lombok.Getter;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

/**
 * This class representing a distribute lock configuration.<br>
 * The minimum granularity of the lock entity is LockUniqueKey, which consists of $UK_PRE_$LockType_$LockTarget,
 * You can set a specified lease time for each lockUniqueKey<p>
 * <p>
 * Sample:<br>
 * LockType: USER_LOCK, LockTartget: 2356784, Lease: 500<br>
 * LockType: USER_LOCK, LockTartget: 2356783, Lease: 500<p>
 * <p>
 * LockType: BATCH_PROCESS_LOCK, LockTartget: MAP_NODE, Lease: 300<br>
 * LockType: BATCH_PROCESS_LOCK, LockTartget: REDUCE_NODE, Lease: 400
 *
 * @author yutianbao
 */

/**
 * 用来存储redis key和过期时间的类
 */
public class DLockConfig implements Serializable {
    private static final long serialVersionUID = -1332663877601479136L;
    /**
     * 用来拼接redis key的前缀
     */
    private static final String UK_PRE = "DLOCK";
    /**
     * 拼接redis key 的连接符
     */
    private static final String UK_SP = "_";

    /**
     * 分布式锁的大类 比如说USER_LOCK,ORDER_LOCK, BATCH_PROCCESS_LOCK等等
     */
    private final String lockType;

    /**
     * 要锁的具体内容, 比如USER_LOCK 的具体哪一个userId, ORDER_LOCK的订单ID
     */
    private final String lockTarget;

    /**
     * 拼接形成的唯一的redis key,拼接规则看构造函数
     */
    @Getter
    private final String lockUniqueKey;

    /**
     * 锁过期时间的值 比如 10 100 1000
     */
    private final int lease;

    /**
     * 锁过期值的单位,一起构成过期时间的唯一值 {@link TimeUnit}
     */
    private final TimeUnit leaseTimeUnit;

    /**
     * 构造函数 必须包含4个参数
     *
     * @param lockType      分布式锁的大类 比如说USER_LOCK,ORDER_LOCK, BATCH_PROCCESS_LOCK等等
     * @param lockTarget    要锁的具体内容, 比如USER_LOCK 的具体哪一个userId, ORDER_LOCK的订单ID
     * @param lease         锁过期时间的值 比如 10 100 1000
     * @param leaseTimeUnit 锁过期值的单位,一起构成过期时间的唯一值 {@link TimeUnit}
     */
    public DLockConfig(String lockType, String lockTarget, int lease, TimeUnit leaseTimeUnit) {
        this.lockType = lockType;
        this.lockTarget = lockTarget;
        this.lease = lease;
        this.leaseTimeUnit = leaseTimeUnit;
        this.lockUniqueKey = UK_PRE + UK_SP + lockType + UK_SP + StringUtils.trimToEmpty(lockTarget);
    }

    /**
     * 获取过期时间,单位为毫秒
     */
    public long getMillisLease() {
        return leaseTimeUnit.toMillis(lease);
    }

    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this, ToStringStyle.DEFAULT_STYLE);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DLockConfig that = (DLockConfig) o;

        return lockUniqueKey != null ? lockUniqueKey.equals(that.lockUniqueKey) : that.lockUniqueKey == null;
    }

    @Override
    public int hashCode() {
        return lockUniqueKey != null ? lockUniqueKey.hashCode() : 0;
    }
}
