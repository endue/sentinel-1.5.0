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

import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.node.OccupyTimeoutProperty;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.PriorityWaitException;
import com.alibaba.csp.sentinel.slots.block.flow.TrafficShapingController;
import com.alibaba.csp.sentinel.util.TimeUtil;

/**
 * Default throttling controller (immediately reject strategy).
 *
 * @author jialiang.linjl
 * @author Eric Zhao
 * 默认流控策略，直接拒绝。
 * 当QPS超过任意规则的阈值后，新的请求就会被立即拒绝，拒绝方式为抛出FlowException。
 * 这种方式适用于对系统处理能力确切已知的情况下，比如通过压测确定了系统的准确水位时
 */
public class DefaultController implements TrafficShapingController {

    private static final int DEFAULT_AVG_USED_TOKENS = 0;

    /**
     * 流控规则中配置的阔值(即一个时间窗口中总的令牌个数)
     */
    private double count;
    /**
     * 流程规则页面配置的"阈值类型"：QPS或线程数
     */
    private int grade;

    public DefaultController(double count, int grade) {
        this.count = count;
        this.grade = grade;
    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        return canPass(node, acquireCount, false);
    }

    /**
     * 校验是否允许请求通过
     * @param node resource node 访问资源对应的Node
     * @param acquireCount count to acquire 当前请求需要获取的数量，默认1
     * @param prioritized whether the request is prioritized
     * @return
     */
    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
        // 获取当前时间node中已通过的thread数或者QPS数，也就是已消耗的token
        int curCount = avgUsedTokens(node);
        // 已消耗token + acquireCount > 规则设定的count,则返回false，否则返回true
        // todo 如果并发执行到这里并没有加锁，所以多个线程都会返回true，限流失效。因为在StatisticSlot中是先放行后统计
        if (curCount + acquireCount > count) {
            // 进入到这里说明请求所要消耗的token已经超过了当前时间窗口的阈值

            // 如果prioritized为true表示优先请求并且流控规则是QPS的情况下对进来的请求进行特殊处理：占用下一个时间窗口的令牌
            if (prioritized && grade == RuleConstant.FLOW_GRADE_QPS) {
                long currentTime;
                long waitInMs;
                currentTime = TimeUtil.currentTimeMillis();
                // 尝试去占用下一个时间窗口的令牌，并返回该时间窗口所剩余的时间waitInMs
                waitInMs = node.tryOccupyNext(currentTime, acquireCount, count);
                // 如果waitInMs小于抢占的最大超时时间，则在下一个时间窗口中增加对应令牌数，并且线程将sleep
                if (waitInMs < OccupyTimeoutProperty.getOccupyTimeout()) {
                    // 占用下一个时间窗口的token
                    node.addWaitingRequest(currentTime + waitInMs, acquireCount);
                    node.addOccupiedPass(acquireCount);
                    sleep(waitInMs);

                    // PriorityWaitException indicates that the request will pass after waiting for {@link @waitInMs}.
                    // 在StatisticSlot中捕获了PriorityWaitException没有继续抛出并且还对该请求进行了放行
                    throw new PriorityWaitException(waitInMs);
                }
            }
            return false;
        }
        return true;
    }

    /**
     * 根据"阈值类型"获取线程数或者QPS的数量
     * @param node
     * @return
     */
    private int avgUsedTokens(Node node) {
        if (node == null) {
            return DEFAULT_AVG_USED_TOKENS;
        }
        return grade == RuleConstant.FLOW_GRADE_THREAD ? node.curThreadNum() : (int)(node.passQps());
    }

    private void sleep(long timeMillis) {
        try {
            Thread.sleep(timeMillis);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }
}
