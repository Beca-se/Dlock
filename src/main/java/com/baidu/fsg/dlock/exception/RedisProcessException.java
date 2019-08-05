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
package com.baidu.fsg.dlock.exception;

/**
 * RedisProcessException
 *
 * @author yutianbao
 */

/**
 * 当操作redis发生错误时,会抛出此异常
 */
public class RedisProcessException extends DLockProcessException {

    /**
     * Serial Version UID
     */
    private static final long serialVersionUID = -4147467240172878091L;

    /**
     * 默认构造函数
     */
    public RedisProcessException() {
        super();
    }


    /**
     * 构造函数包含错误信息,和错误原因
     *
     * @param message 错误信息
     * @param cause   错误栈信息
     */

    public RedisProcessException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 构造函数包含错误信息
     *
     * @param message 错误信息
     */
    public RedisProcessException(String message) {
        this(message, null);
    }


}
