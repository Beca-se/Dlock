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

import java.io.Serializable;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;

/**
 * DLockEntity represents an distributed lock entity, consists of  lock status, locker, lockTime.
 *
 * @author chenguoqing
 * @author yutianbao
 */

/**
 * redis的实体对象
 */
@Getter
@Setter
@ToString
public class DLockEntity implements Serializable {
    private static final long serialVersionUID = 8479390959137749786L;

    /**
     * 锁的状态,默认为没锁住 {@link DLockStatus#INITIAL}
     */
    private DLockStatus lockStatus = DLockStatus.INITIAL;

    /**
     * redis 的value
     */
    private String locker;

    /**
     * 开始加锁的时间
     */
    private Long lockTime = -1L;

    /**
     * 默认构造函数
     */
    public DLockEntity() {
    }

}
