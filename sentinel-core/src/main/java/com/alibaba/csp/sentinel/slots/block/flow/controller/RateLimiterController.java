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
package com.alibaba.csp.sentinel.slots.block.flow.controller;

import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.csp.sentinel.slots.block.flow.TrafficShapingController;

import com.alibaba.csp.sentinel.util.TimeUtil;
import com.alibaba.csp.sentinel.node.Node;

/**
 * @author jialiang.linjl
 * 漏桶算法，达到匀速通过请求，流控效果为排队等待
 */
public class RateLimiterController implements TrafficShapingController {

    /**
     * 每一个请求的最长等待时间ms
     */
    private final int maxQueueingTimeMs;
    /**
     * 每秒通过的请求数，也就是每个请求平均间隔恒定为 1000 / count ms
     * 这里可以看出，该种方式无法满足QPS >1000的场景
     */
    private final double count;

    /**
     * 最近获取到token的时间戳
     */
    private final AtomicLong latestPassedTime = new AtomicLong(-1);

    public RateLimiterController(int timeOut, double count) {
        this.maxQueueingTimeMs = timeOut;
        this.count = count;
    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        return canPass(node, acquireCount, false);
    }

    /**
     * 通过阅读该方法的相关源码可以知道，当突然收到大量请求时，也是会有短暂的洪峰的。
     * 在过一段时间后才会到达匀速模式，下面是测试用例{@link com.alibaba.csp.sentinel.demo.flow.PaceFlowDemo#simulatePulseFlow}的日志：
     * 1634981070077 one request pass, cost 100 ms
     * 1634981070077 one request pass, cost 100 ms
     * 1634981070077 one request pass, cost 100 ms
     * 1634981070077 one request pass, cost 100 ms
     * 1634981070077 one request pass, cost 100 ms
     * 1634981070078 one request pass, cost 105 ms
     * 1634981070174 one request pass, cost 201 ms
     * 1634981070274 one request pass, cost 296 ms
     * 1634981070374 one request pass, cost 397 ms
     * ......
     * @param node resource node
     * @param acquireCount count to acquire
     * @param prioritized whether the request is prioritized
     * @return
     */
    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
        // Pass when acquire count is less or equal than 0.
        if (acquireCount <= 0) {
            return true;
        }
        // Reject when count is less or equal than 0.
        // Otherwise,the costTime will be max of long and waitTime will overflow in some cases.
        if (count <= 0) {
            return false;
        }

        long currentTime = TimeUtil.currentTimeMillis();
        // Calculate the interval between every two requests.
        // 计算此次令牌颁发所需要的时间，其中:(1.0 / count * 1000)代表每个令牌生成的耗时，然后乘以acquireCount得到此次所需令牌生成耗时
        // 匀速限流阀值(count)<=1000才有意义 ，如果count>1000&count<2000,则限流效果和1000是一样的。如果count>2000，则限流间隔costTime=0,实际上就是不限流
        long costTime = Math.round(1.0 * (acquireCount) / count * 1000);

        // Expected pass time of this request.
        // 在上次成功获取token的时间基础上再加上本次的耗时，得到期望通过的时间点
        long expectedTime = costTime + latestPassedTime.get();

        // 如果期望时间 <= 当前时间，那么说明当前令牌充足可以放行，同时将当前时间设置为上次通过时间
        if (expectedTime <= currentTime) {
            // Contention may exist here, but it's okay.
            // todo 这里可以看出当大量并发请求过来时，会存在暂短的洪峰
            latestPassedTime.set(currentTime);
            return true;
        // 期望时间 > 当前时间，令牌不够，需要等待
        } else {
            // Calculate the time to wait.
            long waitTime = costTime + latestPassedTime.get() - TimeUtil.currentTimeMillis();
            // 需要等待时间 > 设置的最大等待时长，直接丢弃，不用等待了
            if (waitTime > maxQueueingTimeMs) {
                return false;
            } else {
                // 需要等待时间 <= 设置的最大等待时长，可以等待
                // 首先更新最近获取token的时间，当下一个请求到达时，会基于最新的token被获取的时间来计算自己所需等待的时间
                // 比如：请求1、请求2同时到达，latestPassedTime此时为10:01:05，
                // 请求1CAS调用addAndGet()成功，更新latestPassedTime为10:01:06
                // 请求2CAS首次失败，再次调用addAndGet()成功，更新latestPassedTime为10:01:07
                long oldTime = latestPassedTime.addAndGet(costTime);
                try {
                    // 重新计算所需等待时间，上一步即使并发调用addAndGet()最终也会让请求排队来获取自己所需的等待时间
                    waitTime = oldTime - TimeUtil.currentTimeMillis();
                    // 超过了阈值，那么由于上面更新了latestPassedTime，所以这里要减掉
                    if (waitTime > maxQueueingTimeMs) {
                        latestPassedTime.addAndGet(-costTime);
                        return false;
                    }
                    // in race condition waitTime may <= 0
                    // 等待一段时间后，通过请求
                    if (waitTime > 0) {
                        Thread.sleep(waitTime);
                    }
                    return true;
                } catch (InterruptedException e) {
                }
            }
        }
        return false;
    }

}
