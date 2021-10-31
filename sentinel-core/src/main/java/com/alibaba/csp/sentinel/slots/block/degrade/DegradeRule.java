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
package com.alibaba.csp.sentinel.slots.block.degrade;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.csp.sentinel.concurrent.NamedThreadFactory;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slots.block.AbstractRule;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;

/**
 * <p>
 * Degrade is used when the resources are in an unstable state, these resources
 * will be degraded within the next defined time window. There are two ways to
 * measure whether a resource is stable or not:
 * </p>
 * <ul>
 * <li>
 * Average response time ({@code DEGRADE_GRADE_RT}): When
 * the average RT exceeds the threshold ('count' in 'DegradeRule', in milliseconds), the
 * resource enters a quasi-degraded state. If the RT of next coming 5
 * requests still exceed this threshold, this resource will be downgraded, which
 * means that in the next time window (defined in 'timeWindow', in seconds) all the
 * access to this resource will be blocked.
 * </li>
 * <li>
 * Exception ratio: When the ratio of exception count per second and the
 * success qps exceeds the threshold, access to the resource will be blocked in
 * the coming window.
 * </li>
 * </ul>
 *
 * @author jialiang.linjl
 */
public class DegradeRule extends AbstractRule {

    /**
     * 默认值为5，目前不支持设定
     */
    private static final int RT_MAX_EXCEED_N = 5;

    private static ScheduledExecutorService pool = Executors.newScheduledThreadPool(
        Runtime.getRuntime().availableProcessors(), new NamedThreadFactory("sentinel-degrade-reset-task", true));

    public DegradeRule() {}

    public DegradeRule(String resourceName) {
        setResource(resourceName);
    }

    /**
     * RT threshold or exception ratio threshold count.
     * 慢调用比例模式下为慢调用临界 RT（超出该值计为慢调用）；异常比例或异常数模式下为对应的阈值
     */
    private double count;

    /**
     * Degrade recover timeout (in seconds) when degradation occurs.
     * 降级时间窗口，单位为s，会在该事件窗口内，程序无法访问
     */
    private int timeWindow;

    /**
     * Degrade strategy (0: average RT, 1: exception ratio).
     * 降级策略，0：调用比例 1：异常比例: 2：异常数策略
     */
    private int grade = RuleConstant.DEGRADE_GRADE_RT;

    /**
     * 是否正处于熔断状态
     */
    private final AtomicBoolean cut = new AtomicBoolean(false);

    public int getGrade() {
        return grade;
    }

    public DegradeRule setGrade(int grade) {
        this.grade = grade;
        return this;
    }

    private AtomicLong passCount = new AtomicLong(0);

    public double getCount() {
        return count;
    }

    public DegradeRule setCount(double count) {
        this.count = count;
        return this;
    }

    private boolean isCut() {
        return cut.get();
    }

    private void setCut(boolean cut) {
        this.cut.set(cut);
    }

    public AtomicLong getPassCount() {
        return passCount;
    }

    public int getTimeWindow() {
        return timeWindow;
    }

    public DegradeRule setTimeWindow(int timeWindow) {
        this.timeWindow = timeWindow;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DegradeRule)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        DegradeRule that = (DegradeRule)o;

        if (count != that.count) {
            return false;
        }
        if (timeWindow != that.timeWindow) {
            return false;
        }
        if (grade != that.grade) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + new Double(count).hashCode();
        result = 31 * result + timeWindow;
        result = 31 * result + grade;
        return result;
    }

    /**
     * 校验是否需要降级
     * @param context current {@link Context}
     * @param node    current {@link com.alibaba.csp.sentinel.node.Node}
     * @param acquireCount
     * @param args    arguments of the original invocation.
     * @return
     */
    @Override
    public boolean passCheck(Context context, DefaultNode node, int acquireCount, Object... args) {
        // 1. 判断是否处于熔断状态，如果是则直接返回
        if (cut.get()) {
            return false;
        }
        // 2. 获取被访问资源的ClusterNode，不存在不降级
        ClusterNode clusterNode = ClusterBuilderSlot.getClusterNode(this.getResource());
        if (clusterNode == null) {
            return true;
        }
        // 下面判断就比较简单了，就是根据当前DegradeRule对应的降级策略进行判断
        // 符合条件就降级，然后生成定时任务。等待timeWindow秒后关闭降级
        // 3. 降级规则中降级策略为RT
        if (grade == RuleConstant.DEGRADE_GRADE_RT) {
            // 3.1 获取资源的ClusterNode中的平均rt，小于阈值不降级
            double rt = clusterNode.avgRt();
            if (rt < this.count) {
                passCount.set(0);
                return true;
            }

            // Sentinel will degrade the service only if count exceeds.
            // 3.2 走到这里说明平均rt大于等于阈值了
            // 但是前RT_MAX_EXCEED_N - 1次，是不降级的，只有连续≥RT_MAX_EXCEED_N时，才降级
            if (passCount.incrementAndGet() < RT_MAX_EXCEED_N) {
                return true;
            }
        // 4. 降级规则中降级策略为异常比例
        } else if (grade == RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO) {
            // 4.1 获取资源的ClusterNode中的平均每个滑动窗口的异常QPS、成功QPS、总QPS
            double exception = clusterNode.exceptionQps();
            double success = clusterNode.successQps();
            double total = clusterNode.totalQps();
            // if total qps less than RT_MAX_EXCEED_N, pass.
            // 4.2 总QPS ＜ RT_MAX_EXCEED_N，不触发降级规则
            if (total < RT_MAX_EXCEED_N) {
                return true;
            }
            // 4.3 成功QPS ＜ RT_MAX_EXCEED_N，不触发降级规则
            double realSuccess = success - exception;
            if (realSuccess <= 0 && exception < RT_MAX_EXCEED_N) {
                return true;
            }
            // 4.4 异常比率 ＜ 阈值，不触发降级规则
            if (exception / success < count) {
                return true;
            }
        // 5.  级规则中降级策略为异常数
        } else if (grade == RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT) {
            // 5.1 获取异常阈值 ＜ 阈值，不触发降级规则
            double exception = clusterNode.totalException();
            if (exception < count) {
                return true;
            }
        }
        // 执行到这里说明触发了熔断，将标识置为true
        // 启动定时任务，在时间窗口过后，将cut置为true，将passCount置为0
        if (cut.compareAndSet(false, true)) {
            ResetTask resetTask = new ResetTask(this);
            pool.schedule(resetTask, timeWindow, TimeUnit.SECONDS);
        }

        return false;
    }

    @Override
    public String toString() {
        return "DegradeRule{" +
            "resource=" + getResource() +
            ", grade=" + grade +
            ", count=" + count +
            ", limitApp=" + getLimitApp() +
            ", timeWindow=" + timeWindow +
            "}";
    }

    private static final class ResetTask implements Runnable {

        private DegradeRule rule;

        ResetTask(DegradeRule rule) {
            this.rule = rule;
        }

        @Override
        public void run() {
            rule.getPassCount().set(0);
            rule.cut.set(false);
        }
    }
}

