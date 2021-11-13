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

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.alibaba.csp.sentinel.cluster.ClusterStateManager;
import com.alibaba.csp.sentinel.cluster.TokenService;
import com.alibaba.csp.sentinel.cluster.client.TokenClientProvider;
import com.alibaba.csp.sentinel.cluster.TokenResult;
import com.alibaba.csp.sentinel.cluster.TokenResultStatus;
import com.alibaba.csp.sentinel.cluster.server.EmbeddedClusterTokenServerProvider;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;

/**
 * Rule checker for parameter flow control.
 *
 * @author Eric Zhao
 * @since 0.2.0
 */
final class ParamFlowChecker {

    /**
     * 校验热点参数是否通过
     * @param resourceWrapper
     * @param rule
     * @param count
     * @param args
     * @return
     */
    static boolean passCheck(ResourceWrapper resourceWrapper, /*@Valid*/ ParamFlowRule rule, /*@Valid*/ int count,
                             Object... args) {
        // 1. 无参数，直接请求通过
        if (args == null) {
            return true;
        }

        // 2. 获取规则中配置的热点参数下标
        int paramIdx = rule.getParamIdx();
        if (args.length <= paramIdx) {
            return true;
        }

        // Get parameter value. If value is null, then pass.
        // 3. 获取访问资源时，对应参数下标位置的值，
        // 如：SphU.entry(resourceName, EntryType.IN, 1, args)
        Object value = args[paramIdx];
        if (value == null) {
            return true;
        }

        if (rule.isClusterMode() && rule.getGrade() == RuleConstant.FLOW_GRADE_QPS) {
            return passClusterCheck(resourceWrapper, rule, count, value);
        }
        // 本地模式
        return passLocalCheck(resourceWrapper, rule, count, value);
    }

    private static boolean passLocalCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule, int count,
                                          Object value) {
        try {
            // 参数是集合类型
            if (Collection.class.isAssignableFrom(value.getClass())) {
                // 遍历集合，获取参数值，一个个校验，有一个不通过则就不通过
                for (Object param : ((Collection)value)) {
                    if (!passSingleValueCheck(resourceWrapper, rule, count, param)) {
                        return false;
                    }
                }
            // 参是数组类型
            } else if (value.getClass().isArray()) {
                // 遍历数组，获取参数值，一个个校验，有一个不通过则就不通过
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    Object param = Array.get(value, i);
                    if (!passSingleValueCheck(resourceWrapper, rule, count, param)) {
                        return false;
                    }
                }
            // 其他类型
            } else {
                return passSingleValueCheck(resourceWrapper, rule, count, value);
            }
        } catch (Throwable e) {
            RecordLog.warn("[ParamFlowChecker] Unexpected error", e);
        }

        return true;
    }

    /**
     * 校验参数值
     * @param resourceWrapper
     * @param rule
     * @param count
     * @param value
     * @return
     */
    static boolean passSingleValueCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule, int count, Object value) {
        // 获取参数排除项
        Set<Object> exclusionItems = rule.getParsedHotItems().keySet();
        // QPS限流模式
        if (rule.getGrade() == RuleConstant.FLOW_GRADE_QPS) {
            // getHotParameters(resourceWrapper) 获取资源下的参数指标
            // getPassParamQps(rule.getParamIdx(), value) 获取参数指标中针对某下标索引的某个值的统计
            double curCount = getHotParameters(resourceWrapper).getPassParamQps(rule.getParamIdx(), value);
            // 当前value值被包含在参数排除项中，则根据配置的参数排除项的阈值来校验本次请求是否通过
            if (exclusionItems.contains(value)) {
                // Pass check for exclusion items.
                int itemQps = rule.getParsedHotItems().get(value);
                return curCount + count <= itemQps;
            // 不在参数排除项中，则根据热点参数规则限流
            } else if (curCount + count > rule.getCount()) {
                // todo 这里什么意思？
                if ((curCount - rule.getCount()) < 1 && (curCount - rule.getCount()) > 0) {
                    return true;
                }
                return false;
            }
        // 线程数限流模式和上面差不多，只不过获取的是线程统计
        } else if (rule.getGrade() == RuleConstant.FLOW_GRADE_THREAD) {
            long threadCount = getHotParameters(resourceWrapper).getThreadCount(rule.getParamIdx(), value);
            if (exclusionItems.contains(value)) {
                int itemThreshold = rule.getParsedHotItems().get(value);
                return ++threadCount <= itemThreshold;
            }
            long threshold = (long)rule.getCount();
            return ++threadCount <= threshold;
        }

        return true;
    }

    /**
     * 获取资源下的参数指标
     * @param resourceWrapper
     * @return
     */
    private static ParameterMetric getHotParameters(ResourceWrapper resourceWrapper) {
        // Should not be null.
        return ParamFlowSlot.getParamMetric(resourceWrapper);
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> toCollection(Object value) {
        if (value instanceof Collection) {
            return (Collection<Object>)value;
        } else if (value.getClass().isArray()) {
            List<Object> params = new ArrayList<Object>();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object param = Array.get(value, i);
                params.add(param);
            }
            return params;
        } else {
            return Collections.singletonList(value);
        }
    }

    private static boolean passClusterCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule, int count,
                                            Object value) {
        try {
            Collection<Object> params = toCollection(value);

            TokenService clusterService = pickClusterService();
            if (clusterService == null) {
                // No available cluster client or server, fallback to local or pass in need.
                return fallbackToLocalOrPass(resourceWrapper, rule, count, params);
            }

            TokenResult result = clusterService.requestParamToken(rule.getClusterConfig().getFlowId(), count, params);
            switch (result.getStatus()) {
                case TokenResultStatus.OK:
                    return true;
                case TokenResultStatus.BLOCKED:
                    return false;
                default:
                    return fallbackToLocalOrPass(resourceWrapper, rule, count, params);
            }
        } catch (Throwable ex) {
            RecordLog.warn("[ParamFlowChecker] Request cluster token for parameter unexpected failed", ex);
            return fallbackToLocalOrPass(resourceWrapper, rule, count, value);
        }
    }

    private static boolean fallbackToLocalOrPass(ResourceWrapper resourceWrapper, ParamFlowRule rule, int count,
                                                 Object value) {
        if (rule.getClusterConfig().isFallbackToLocalWhenFail()) {
            return passLocalCheck(resourceWrapper, rule, count, value);
        } else {
            // The rule won't be activated, just pass.
            return true;
        }
    }

    private static TokenService pickClusterService() {
        if (ClusterStateManager.isClient()) {
            return TokenClientProvider.getClient();
        }
        if (ClusterStateManager.isServer()) {
            return EmbeddedClusterTokenServerProvider.getServer();
        }
        return null;
    }

    private ParamFlowChecker() {}
}
