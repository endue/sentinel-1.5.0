/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.statistic.data;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.alibaba.csp.sentinel.slots.block.flow.param.RollingParamEvent;
import com.alibaba.csp.sentinel.slots.statistic.cache.CacheMap;
import com.alibaba.csp.sentinel.slots.statistic.cache.ConcurrentLinkedHashMapWrapper;
import com.alibaba.csp.sentinel.util.AssertUtil;

/**
 * Represents metric bucket of frequent parameters in a period of time window.
 *
 * @author Eric Zhao
 * @since 0.2.0
 */
public class ParamMapBucket {

    /**
     * data是一个数组，用来区分不同请求类型下的统计
     * key是参数具体的值，vaue是计数
     */
    private final CacheMap<Object, AtomicInteger>[] data;

    public ParamMapBucket() {
        this(DEFAULT_MAX_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ParamMapBucket(int capacity) {
        AssertUtil.isTrue(capacity > 0, "capacity should be positive");
        RollingParamEvent[] events = RollingParamEvent.values();
        this.data = new CacheMap[events.length];
        for (RollingParamEvent event : events) {
            data[event.ordinal()] = new ConcurrentLinkedHashMapWrapper<Object, AtomicInteger>(capacity);
        }
    }

    public void reset() {
        for (RollingParamEvent event : RollingParamEvent.values()) {
            data[event.ordinal()].clear();
        }
    }

    public int get(RollingParamEvent event, Object value) {
        AtomicInteger counter = data[event.ordinal()].get(value);
        return counter == null ? 0 : counter.intValue();
    }

    /**
     * 增加对应请求类型的数量
     * @param event
     * @param count
     * @param value
     * @return
     */
    public ParamMapBucket add(RollingParamEvent event, int count, Object value) {
        // data[event.ordinal()] 获取对应请求类型的CacheMap<Object, AtomicInteger>
        // 然后从CacheMap中获取具体值的统计AtomicInteger
        AtomicInteger counter = data[event.ordinal()].get(value);
        // Note: not strictly concise.
        // 没有就新建在增加，有就直接增加
        if (counter == null) {
            AtomicInteger old = data[event.ordinal()].putIfAbsent(value, new AtomicInteger(count));
            if (old != null) {
                old.addAndGet(count);
            }
        } else {
            counter.addAndGet(count);
        }
        return this;
    }

    public Set<Object> ascendingKeySet(RollingParamEvent type) {
        return data[type.ordinal()].keySet(true);
    }

    public Set<Object> descendingKeySet(RollingParamEvent type) {
        return data[type.ordinal()].keySet(false);
    }

    public static final int DEFAULT_MAX_CAPACITY = 200;
}