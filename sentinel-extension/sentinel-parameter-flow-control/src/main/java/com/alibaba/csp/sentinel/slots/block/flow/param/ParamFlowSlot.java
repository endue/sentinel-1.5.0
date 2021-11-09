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
package com.alibaba.csp.sentinel.slots.block.flow.param;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slotchain.StringResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.util.StringUtil;

/**
 * A processor slot that is responsible for flow control by frequent ("hot spot") parameters.
 *
 * @author jialiang.linjl
 * @author Eric Zhao
 * @since 0.2.0
 */
public class ParamFlowSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

    /**
     * 资源和对应的参数统计
     */
    private static final Map<ResourceWrapper, ParameterMetric> metricsMap
        = new ConcurrentHashMap<ResourceWrapper, ParameterMetric>();

    /**
     * Lock for a specific resource.
     */
    private final Object LOCK = new Object();

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count,
                      boolean prioritized, Object... args) throws Throwable {
        if (!ParamFlowRuleManager.hasRules(resourceWrapper.getName())) {
            fireEntry(context, resourceWrapper, node, count, prioritized, args);
            return;
        }

        checkFlow(resourceWrapper, count, args);
        fireEntry(context, resourceWrapper, node, count, prioritized, args);
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        fireExit(context, resourceWrapper, count, args);
    }

    /**
     * 热点参数校验
     * @param resourceWrapper
     * @param count
     * @param args
     * @throws BlockException
     */
    void checkFlow(ResourceWrapper resourceWrapper, int count, Object... args)
        throws BlockException {
        // 首先校验要访问的资源是否有热点参数限流
        if (ParamFlowRuleManager.hasRules(resourceWrapper.getName())) {
            // 获取资源对应的热点参数规则ParamFlowRule
            List<ParamFlowRule> rules = ParamFlowRuleManager.getRulesOfResource(resourceWrapper.getName());
            if (rules == null) {
                return;
            }
            // 遍历所有ParamFlowRule
            for (ParamFlowRule rule : rules) {
                // 获取热点参数规则中配置的参数下标
                int paramIdx = rule.getParamIdx();
                // 参数是倒着填写的，重新计算参数位置
                if (paramIdx < 0) {
                    if (-paramIdx <= args.length) {
                        rule.setParamIdx(args.length + paramIdx);
                    } else {
                        // illegal index, give it a illegal positive value, latter rule check will pass
                        // 非法索引，给它一个非法的正数，后规则检查将通过
                        rule.setParamIdx(-paramIdx);
                    }
                }

                // Initialize the parameter metrics.
                // 初始化该资源下，该参数对应的令牌桶
                initHotParamMetricsFor(resourceWrapper, rule.getParamIdx());
                // 获取令牌，失败后将抛出ParamFlowException异常
                if (!ParamFlowChecker.passCheck(resourceWrapper, rule, count, args)) {

                    // Here we add the block count.
                    addBlockCount(resourceWrapper, count, args);

                    String triggeredParam = "";
                    if (args.length > rule.getParamIdx()) {
                        Object value = args[rule.getParamIdx()];
                        triggeredParam = String.valueOf(value);
                    }
                    throw new ParamFlowException(resourceWrapper.getName(), triggeredParam, rule);
                }
            }
        }
    }

    private void addBlockCount(ResourceWrapper resourceWrapper, int count, Object... args) {
        ParameterMetric parameterMetric = ParamFlowSlot.getParamMetric(resourceWrapper);

        if (parameterMetric != null) {
            parameterMetric.addBlock(count, args);
        }
    }

    /**
     * Init the parameter metric and index map for given resource.
     * Package-private for test.
     *
     * @param resourceWrapper resource to init 被访问的资源
     * @param index           index to initialize, which must be valid 参数下标位置
     */
    void initHotParamMetricsFor(ResourceWrapper resourceWrapper, /*@Valid*/ int index) {
        ParameterMetric metric;
        // Assume that the resource is valid.
        if ((metric = metricsMap.get(resourceWrapper)) == null) {
            synchronized (LOCK) {
                if ((metric = metricsMap.get(resourceWrapper)) == null) {
                    metric = new ParameterMetric();
                    metricsMap.put(resourceWrapper, metric);
                    RecordLog.info("[ParamFlowSlot] Creating parameter metric for: " + resourceWrapper.getName());
                }
            }
        }
        metric.initializeForIndex(index);
    }

    /**
     * 获取资源下所有热点参数统计指标
     * @param resourceWrapper
     * @return
     */
    public static ParameterMetric getParamMetric(ResourceWrapper resourceWrapper) {
        if (resourceWrapper == null || resourceWrapper.getName() == null) {
            return null;
        }
        return metricsMap.get(resourceWrapper);
    }

    public static ParameterMetric getHotParamMetricForName(String resourceName) {
        if (StringUtil.isBlank(resourceName)) {
            return null;
        }
        for (EntryType nodeType : EntryType.values()) {
            ParameterMetric metric = metricsMap.get(new StringResourceWrapper(resourceName, nodeType));
            if (metric != null) {
                return metric;
            }
        }
        return null;
    }

    static void clearHotParamMetricForName(String resourceName) {
        if (StringUtil.isBlank(resourceName)) {
            return;
        }
        for (EntryType nodeType : EntryType.values()) {
            metricsMap.remove(new StringResourceWrapper(resourceName, nodeType));
        }
        RecordLog.info("[ParamFlowSlot] Clearing parameter metric for: " + resourceName);
    }

    public static Map<ResourceWrapper, ParameterMetric> getMetricsMap() {
        return metricsMap;
    }
}
