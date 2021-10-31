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
package com.alibaba.csp.sentinel.node;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.alibaba.csp.sentinel.util.TimeUtil;
import com.alibaba.csp.sentinel.node.metric.MetricNode;
import com.alibaba.csp.sentinel.slots.statistic.metric.ArrayMetric;
import com.alibaba.csp.sentinel.slots.statistic.metric.Metric;

/**
 * <p>The statistic node keep three kinds of real-time statistics metrics:</p>
 * <ol>
 * <li>metrics in second level ({@code rollingCounterInSecond})</li>
 * <li>metrics in minute level ({@code rollingCounterInMinute})</li>
 * <li>thread count</li>
 * </ol>
 *
 * <p>
 * Sentinel use sliding window to record and count the resource statistics in real-time.
 * The sliding window infrastructure behind the {@link ArrayMetric} is {@code LeapArray}.
 * </p>
 *
 * <p>
 * case 1: When the first request comes in, Sentinel will create a new window bucket of
 * a specified time-span to store running statics, such as total response time(rt),
 * incoming request(QPS), block request(bq), etc. And the time-span is defined by sample count.
 * </p>
 * <pre>
 * 	0      100ms
 *  +-------+--→ Sliding Windows
 * 	    ^
 * 	    |
 * 	  request
 * </pre>
 * <p>
 * Sentinel use the statics of the valid buckets to decide whether this request can be passed.
 * For example, if a rule defines that only 100 requests can be passed,
 * it will sum all qps in valid buckets, and compare it to the threshold defined in rule.
 * </p>
 *
 * <p>case 2: continuous requests</p>
 * <pre>
 *  0    100ms    200ms    300ms
 *  +-------+-------+-------+-----→ Sliding Windows
 *                      ^
 *                      |
 *                   request
 * </pre>
 *
 * <p>case 3: requests keeps coming, and previous buckets become invalid</p>
 * <pre>
 *  0    100ms    200ms	  800ms	   900ms  1000ms    1300ms
 *  +-------+-------+ ...... +-------+-------+ ...... +-------+-----→ Sliding Windows
 *                                                      ^
 *                                                      |
 *                                                    request
 * </pre>
 *
 * <p>The sliding window should become:</p>
 * <pre>
 * 300ms     800ms  900ms  1000ms  1300ms
 *  + ...... +-------+ ...... +-------+-----→ Sliding Windows
 *                                                      ^
 *                                                      |
 *                                                    request
 * </pre>
 *
 * @author qinan.qn
 * @author jialiang.linjl
 * 记录了各种统计信息
 */
public class StatisticNode implements Node {

    /**
     * Holds statistics of the recent {@code INTERVAL} seconds. The {@code INTERVAL} is divided into time spans
     * by given {@code sampleCount}.
     * 采样个数2，采样时间1000ms.也就是说这里总共分了2个采样的窗口，每个时间窗口的长度是500ms
     */
    private transient volatile Metric rollingCounterInSecond = new ArrayMetric(SampleCountProperty.SAMPLE_COUNT,
        IntervalProperty.INTERVAL);

    /**
     * Holds statistics of the recent 60 seconds. The windowLengthInMs is deliberately set to 1000 milliseconds,
     * meaning each bucket per second, in this way we can get accurate statistics of each second.
     * 采样个数60，采样时间60 * 1000ms.也就是说这里总共分了60个采样的窗口，每个时间窗口的长度是1s
     */
    private transient Metric rollingCounterInMinute = new ArrayMetric(60, 60 * 1000, false);

    /**
     * The counter for thread count.
     */
    private AtomicInteger curThreadNum = new AtomicInteger(0);

    /**
     * The last timestamp when metrics were fetched.
     */
    private long lastFetchTime = -1;

    @Override
    public Map<Long, MetricNode> metrics() {
        // The fetch operation is thread-safe under a single-thread scheduler pool.
        long currentTime = TimeUtil.currentTimeMillis();
        currentTime = currentTime - currentTime % 1000;
        Map<Long, MetricNode> metrics = new ConcurrentHashMap<>();
        List<MetricNode> nodesOfEverySecond = rollingCounterInMinute.details();
        long newLastFetchTime = lastFetchTime;
        // Iterate metrics of all resources, filter valid metrics (not-empty and up-to-date).
        for (MetricNode node : nodesOfEverySecond) {
            if (isNodeInTime(node, currentTime) && isValidMetricNode(node)) {
                metrics.put(node.getTimestamp(), node);
                newLastFetchTime = Math.max(newLastFetchTime, node.getTimestamp());
            }
        }
        lastFetchTime = newLastFetchTime;

        return metrics;
    }

    private boolean isNodeInTime(MetricNode node, long currentTime) {
        return node.getTimestamp() > lastFetchTime && node.getTimestamp() < currentTime;
    }

    private boolean isValidMetricNode(MetricNode node) {
        return node.getPassQps() > 0 || node.getBlockQps() > 0 || node.getSuccessQps() > 0
            || node.getExceptionQps() > 0 || node.getRt() > 0 || node.getOccupiedPassQps() > 0;
    }

    @Override
    public void reset() {
        rollingCounterInSecond = new ArrayMetric(SampleCountProperty.SAMPLE_COUNT, IntervalProperty.INTERVAL);
    }

    @Override
    public long totalRequest() {
        long totalRequest = rollingCounterInMinute.pass() + rollingCounterInMinute.block();
        return totalRequest;
    }

    @Override
    public long blockRequest() {
        return rollingCounterInMinute.block();
    }

    @Override
    public double blockQps() {
        return rollingCounterInSecond.block() / rollingCounterInSecond.getWindowIntervalInSec();
    }

    @Override
    public double previousBlockQps() {
        return this.rollingCounterInMinute.previousWindowBlock();
    }

    @Override
    public double previousPassQps() {
        return this.rollingCounterInMinute.previousWindowPass();
    }

    @Override
    public double totalQps() {
        return passQps() + blockQps();
    }

    @Override
    public long totalSuccess() {
        return rollingCounterInMinute.success();
    }

    /**
     * 获取所有滑动窗口的异常总数，然后除以滑动窗口的个数，求平均每个滑动窗口的异常值
     * @return
     */
    @Override
    public double exceptionQps() {
        return rollingCounterInSecond.exception() / rollingCounterInSecond.getWindowIntervalInSec();
    }

    @Override
    public long totalException() {
        return rollingCounterInMinute.exception();
    }

    @Override
    public double passQps() {
        return rollingCounterInSecond.pass() / rollingCounterInSecond.getWindowIntervalInSec();
    }

    @Override
    public long totalPass() {
        return rollingCounterInMinute.pass();
    }

    @Override
    public double successQps() {
        return rollingCounterInSecond.success() / rollingCounterInSecond.getWindowIntervalInSec();
    }

    @Override
    public double maxSuccessQps() {
        return rollingCounterInSecond.maxSuccess() * rollingCounterInSecond.getSampleCount();
    }

    @Override
    public double occupiedPassQps() {
        return rollingCounterInSecond.occupiedPass() / rollingCounterInSecond.getWindowIntervalInSec();
    }

    @Override
    public double avgRt() {
        long successCount = rollingCounterInSecond.success();
        if (successCount == 0) {
            return 0;
        }

        return rollingCounterInSecond.rt() * 1.0 / successCount;
    }

    @Override
    public double minRt() {
        return rollingCounterInSecond.minRt();
    }

    @Override
    public int curThreadNum() {
        return curThreadNum.get();
    }

    @Override
    public void addPassRequest(int count) {
        rollingCounterInSecond.addPass(count);
        rollingCounterInMinute.addPass(count);
    }

    @Override
    public void addRtAndSuccess(long rt, int successCount) {
        rollingCounterInSecond.addSuccess(successCount);
        rollingCounterInSecond.addRT(rt);

        rollingCounterInMinute.addSuccess(successCount);
        rollingCounterInMinute.addRT(rt);
    }

    @Override
    public void increaseBlockQps(int count) {
        rollingCounterInSecond.addBlock(count);
        rollingCounterInMinute.addBlock(count);
    }

    @Override
    public void increaseExceptionQps(int count) {
        rollingCounterInSecond.addException(count);
        rollingCounterInMinute.addException(count);
    }

    @Override
    public void increaseThreadNum() {
        curThreadNum.incrementAndGet();
    }

    @Override
    public void decreaseThreadNum() {
        curThreadNum.decrementAndGet();
    }

    @Override
    public void debug() {
        rollingCounterInSecond.debug();
    }

    /**
     * 尝试占用后面某个时间窗口的令牌
     * @param currentTime  current time millis. 调用该方法时，获取的当前时间戳
     * @param acquireCount tokens count to acquire. 要获取的token数
     * @param threshold    qps threshold. QPS阈值
     * @return
     */
    @Override
    public long tryOccupyNext(long currentTime, int acquireCount, double threshold) {
        // threshold是一个时间窗口的token阈值
        // IntervalProperty.INTERVAL / 1000是有多少个时间窗口
        // 最终计算出来的就是一个采样周期token的个数
        double maxCount = threshold * IntervalProperty.INTERVAL / 1000;
        // 计算采用周期内已被占用的token个数
        long currentBorrow = rollingCounterInSecond.waiting();
        // 超过阈值了，返回OccupyTimeout。因为此时已经无法占用下一个时间窗口了
        if (currentBorrow >= maxCount) {
            return OccupyTimeoutProperty.getOccupyTimeout();
        }
        // 计算时间窗口的长度
        int windowLength = IntervalProperty.INTERVAL / SampleCountProperty.SAMPLE_COUNT;
        /**
         * 计算currentTime所在采用窗口的前一个采用窗口的开始时间
         *
         *  0                   500ms               1000ms
         *  +--------------------+--------------------→ 滑动窗口
         *  ^                                ^
         *  |                                |
         * earliestTime                 currentTime
         */
        long earliestTime = currentTime - currentTime % windowLength + windowLength - IntervalProperty.INTERVAL;

        int idx = 0;
        /*
         * Note: here {@code currentPass} may be less than it really is NOW, because time difference
         * since call rollingCounterInSecond.pass(). So in high concurrency, the following code may
         * lead more tokens be borrowed.
         * todo: 这里有高并发问题，就是当大量请求同时到达rollingCounterInSecond.pass()前后计算出来的值不一样
         * 比如：刚开始调用后currentPass为23，突然大量请求过来rollingCounterInSecond.pass()再次调用已经是56了
         * 所以最终会导致更多的token被获取
         */
        // 计算采用周期内已使用的token
        long currentPass = rollingCounterInSecond.pass();
        while (earliestTime < currentTime) {
            /**
             * windowLength - currentTime % windowLength 计算当前时间距离下一个采用窗口还有多久
             * idx * windowLength 表示占用未来第几个采用窗口的token，首次idx为0，占用下一个的，因为后面还加上了距离下一个采用窗口的时间
             *
             *  0                   500ms               1000ms
             *  +--------------------+--------------------→ 滑动窗口
             *                             ^←-------------→
             *                             |        ^
             *                         currentTime  |
             *                                      |
             *                      windowLength - currentTime % windowLength
             */
            long waitInMs = idx * windowLength + windowLength - currentTime % windowLength;
            // 计算出来的等待时间，已经超过最大等待时间了，退出即可
            if (waitInMs >= OccupyTimeoutProperty.getOccupyTimeout()) {
                break;
            }
            // 计算currentTime所在时间窗口的前一个时间窗口已使用的token数
            long windowPass = rollingCounterInSecond.getWindowPass(earliestTime);
            /**
             * 计算当前时间窗口是否还有token,如果有直接返回等待时间即可
             * 提醒：这里针对的是QPS，假设QPS为10，也就是每秒10个token，根据代码中的内容可知rollingCounterInSecond将QPS规划为两个滑动时间窗口
             * 用下图表示，其中未来的1000-1500ms占用了0-500ms的时间窗口：
             *          编号0                 编号1                 编号0
             *  0                   500ms               1000ms                1500ms
             *  +--------------------+--------------------+--------------------→ 滑动窗口
             *                                ^
             *                                |
             *                           currentTime
             *  ←------------   currentPass    ----------→
             *  ←----windowPass----→
             *                       ←------------   currentBorrow   ----------→
             *                       ←---acquireCount----→
             *                       ←------------   剩余token       ----------→
             *  currentPass + currentBorrow + acquireCount - windowPass计算的就是剩余token
             *  1. currentPass - windowPass计算当前时间窗口剩余token数
             *  2. currentBorrow + acquireCount计算的是打算占用的token数
             *  3. maxCount就是当前时间窗口和未来某个时间窗口总的token数
             *  4. 最终计算的就是当前时间窗口和未来某个时间窗口剩余的token数，有就返回等待时间。到达该时间后也就到达了未来的那个时间窗口
             */
            if (currentPass + currentBorrow + acquireCount - windowPass <= maxCount) {
                return waitInMs;
            }
            // 走到这里说明当前时间窗口没有剩余的token满足acquireCount，需要继续看后面的时间窗口是否满足条件
            // 计算下一个时间窗口的开始时间
            earliestTime += windowLength;
            // 计算下一个时间窗口已使用的token
            currentPass -= windowPass;
            // +1操作计算下一个时间窗口
            idx++;
        }

        return OccupyTimeoutProperty.getOccupyTimeout();
    }

    @Override
    public long waiting() {
        return rollingCounterInSecond.waiting();
    }

    /**
     * 占用未来时间点futureTime对应的时间窗口的token
     * @param futureTime   future timestamp that the acquireCount should be added on.
     * @param acquireCount tokens count.
     */
    @Override
    public void addWaitingRequest(long futureTime, int acquireCount) {
        rollingCounterInSecond.addWaiting(futureTime, acquireCount);
    }

    @Override
    public void addOccupiedPass(int acquireCount) {
        rollingCounterInMinute.addOccupiedPass(acquireCount);
        rollingCounterInMinute.addPass(acquireCount);
    }
}
