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
package com.baidu.fsg.dlock.jedis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

/**
 * 操作redis的直接类
 *
 * @author yutianbao
 */

public class JedisClient {

    private final JedisPool jedisPool;

    public JedisClient(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    /**
     * 获取value透过key
     *
     * @param key redis key
     * @return 返回获取到的value, 如果没有获取到, 返回值为null
     */
    public String get(String key) {
        Jedis jedis = null;
        try {
            jedis = jedisPool.getResource();
            return jedis.get(key);

        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * 使用redis做分布式锁的核心方法 set(final String key, final String value,NX, PX,final long time)
     *
     * @param key   redis key
     * @param value redis value
     * @param nxxx  NX | XX 可选,在这里全部使用NX,NX: 即只有这个key不存在的时候才会设置, XX ：只在键已经存在时，才对键进行设置操作。
     * @param expx  PX | EX 可选,这里使用PX,EX ：设置键的过期时间为秒, PX：设置键的过期时间为毫秒
     * @param time  过期时间,单位为毫秒
     * @return 是否设置成功, 如果不成功返回null, 成功返回OK
     */
    public String set(String key, String value, String nxxx, String expx, long time) {
        Jedis jedis = null;
        try {
            jedis = jedisPool.getResource();
            return jedis.set(key, value, nxxx, expx, time);

        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * 通过lua脚本删除key
     *
     * @param script lua 脚本
     * @param keys   所有的key 参数
     * @param args   所有的参数
     * @return 执行结果
     */
    public Object eval(String script, List<String> keys, List<String> args) {
        Jedis jedis = null;
        try {
            jedis = jedisPool.getResource();
            return jedis.eval(script, keys, args);

        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }


}
