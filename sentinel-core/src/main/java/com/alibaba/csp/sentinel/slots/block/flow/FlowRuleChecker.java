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
package com.alibaba.csp.sentinel.slots.block.flow;

import com.alibaba.csp.sentinel.cluster.ClusterStateManager;
import com.alibaba.csp.sentinel.cluster.server.EmbeddedClusterTokenServerProvider;
import com.alibaba.csp.sentinel.cluster.client.TokenClientProvider;
import com.alibaba.csp.sentinel.cluster.TokenResultStatus;
import com.alibaba.csp.sentinel.cluster.TokenResult;
import com.alibaba.csp.sentinel.cluster.TokenService;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;
import com.alibaba.csp.sentinel.util.StringUtil;

/**
 * Rule checker for flow control rules.
 * 流控规则检查器
 * @author Eric Zhao
 */
final class FlowRuleChecker {

    /**
     * 根据流控规则对流量进行控制
     * @param rule
     * @param context
     * @param node
     * @param acquireCount
     * @return
     */
    static boolean passCheck(/*@NonNull*/ FlowRule rule, Context context, DefaultNode node, int acquireCount) {
        return passCheck(rule, context, node, acquireCount, false);
    }

    static boolean passCheck(/*@NonNull*/ FlowRule rule, Context context, DefaultNode node, int acquireCount,
                                          boolean prioritized) {
        // 获取控制台页面上的"针对来源"的值，默认值default
        String limitApp = rule.getLimitApp();
        if (limitApp == null) {
            return true;
        }
        // 获取控制台页面上的"是否集群"的值,默认未勾选
        if (rule.isClusterMode()) {
            // 集群流控
            return passClusterCheck(rule, context, node, acquireCount, prioritized);
        }
        // 使用本地流控
        return passLocalCheck(rule, context, node, acquireCount, prioritized);
    }

    /**
     * 集群模式限流实现
     * @param rule
     * @param context
     * @param node
     * @param acquireCount
     * @param prioritized
     * @return
     */
    private static boolean passClusterCheck(FlowRule rule, Context context, DefaultNode node, int acquireCount,
                                            boolean prioritized) {
        try {
            TokenService clusterService = pickClusterService();
            if (clusterService == null) {
                return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
            }
            // 获取流控规则中flowId,保证全局唯一
            long flowId = rule.getClusterConfig().getFlowId();
            TokenResult result = clusterService.requestToken(flowId, acquireCount, prioritized);
            // 解析响应
            return applyTokenResult(result, rule, context, node, acquireCount, prioritized);
            // If client is absent, then fallback to local mode.
        } catch (Throwable ex) {
            RecordLog.warn("[FlowRuleChecker] Request cluster token unexpected failed", ex);
        }
        // Fallback to local flow control when token client or server for this rule is not available.
        // If fallback is not enabled, then directly pass.
        return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
    }

    /**
     * 本地模式限流实现
     * @param rule
     * @param context
     * @param node
     * @param acquireCount
     * @param prioritized
     * @return
     */
    private static boolean passLocalCheck(FlowRule rule, Context context, DefaultNode node, int acquireCount,
                                          boolean prioritized) {
        // 选择节点
        Node selectedNode = selectNodeByRequesterAndStrategy(rule, context, node);
        // 注意这里，如果找不到Node就不限流了
        if (selectedNode == null) {
            return true;
        }
        // 流控规则校验.rule.getRater()获取流控效果：快速失败、Warm Up、排队等待
        return rule.getRater().canPass(selectedNode, acquireCount, prioritized);
    }

    /**
     * 根据不同情况选择不同Node：DefaultNode、ClusterNode、OriginNode(StatisticNode)...
     * @param rule
     * @param context
     * @param node
     * @return
     */
    static Node selectNodeByRequesterAndStrategy(/*@NonNull*/ FlowRule rule, Context context, DefaultNode node) {
        // The limit app should not be empty.
        // 获取"针对来源",也就是流控针对的调用来源,若为default则不区分调用来源
        String limitApp = rule.getLimitApp();
        // 获取"流控模式" 0：直连 1：关联 2：链路
        int strategy = rule.getStrategy();
        // 获取当前线程上下文中设置的调用来源,默认为""
        // 用户访问资源前可手动设置：ContextUtil.enter(resource, origin)
        String origin = context.getOrigin();
        // 如下: 配置流控规则针对调用来源为userCenter的对资源A的访问QPS为1,
        //  FlowRule rule1 = new FlowRule();
        //  rule1.setResource("A");
        //  rule1.setCount(1);
        //  rule1.setGrade(RuleConstant.FLOW_GRADE_QPS);
        //  rule1.setLimitApp("userCenter");
        // 1. 判断调用来源origin与当前流控规则的针对来源limitApp是否匹配并且调用来源origin不是"default"或者"other"这种值
        if (limitApp.equals(origin) && filterOrigin(origin)) {
            // 1.1 "流控模式"为"直接",获取针对来源limitApp的全局统计数据对应的Node，也就是OriginNode
            if (strategy == RuleConstant.STRATEGY_DIRECT) {
                // Matches limit origin, return origin statistic node.
                return context.getOriginNode();
            }
            // 1.2 "流控模式"为"关联"或"链路"
            return selectReferenceNode(rule, context, node);
        // 2. 当前流控规则针对来源为"default",也就是默认的流控规则
        } else if (RuleConstant.LIMIT_APP_DEFAULT.equals(limitApp)) {
            // 2.1 "流控模式"为直接,获取当前线程持有的当前资源A的ClusterNode,因为这种不区分调用来源，只针对当前资源A进行全局的流控
            if (strategy == RuleConstant.STRATEGY_DIRECT) {
                // Return the cluster node.
                return node.getClusterNode();
            }
            // 2.2 "流控模式"为"关联"或"链路"
            return selectReferenceNode(rule, context, node);
        // 3. 这里并不是表示当前流控规则针对的调用来源为"other"而是在设置了limitApp为other值后
        // 如:注释中增加rule2然后设置rule2.setLimitApp("other")，其他配置一样。那么rule2只会处理调用来源为非"userCenter"的请求
        } else if (RuleConstant.LIMIT_APP_OTHER.equals(limitApp)
                && FlowRuleManager.isOtherOrigin(origin, rule.getResource())) {
            // // 3.1 "流控模式"为"直接",获取针对来源limitApp的全局统计数据对应的Node，也就是OriginNode
            if (strategy == RuleConstant.STRATEGY_DIRECT) {
                return context.getOriginNode();
            }
            // 3.2 "流控模式"为"关联"或"链路"
            return selectReferenceNode(rule, context, node);
        }

        return null;
    }

    /**
     * 当流控规则页面上"流控模式"为"关联"或者"链路"，获取对应资源的Node
     * @param rule
     * @param context
     * @param node
     * @return
     */
    static Node selectReferenceNode(FlowRule rule, Context context, DefaultNode node) {
        // "流控模式"为"关联"或者"链路"时，获取对应的"关联资源"或"入口资源"
        String refResource = rule.getRefResource();
        // 获取"流控模式"
        int strategy = rule.getStrategy();
        // "关联资源"或"入口资源"未配置，也就表示没有关联任何资源的node，所以返回null
        if (StringUtil.isEmpty(refResource)) {
            return null;
        }
        // 流控模式为"关联"，获取关联资源refResource的ClusterNode，参考官网案例{@link https://github.com/alibaba/Sentinel/wiki/%E6%B5%81%E9%87%8F%E6%8E%A7%E5%88%B6}
        // 以官网write_db和read_db为例，当read_db请求的时候，是把write_db的ClusterNode与规则进行比较，
        // 那么问题就有答案了，假设write_db一直没有请求，那么read_db就没有限制，因为write_db的ClusterNode数据为空
        if (strategy == RuleConstant.STRATEGY_RELATE) {
            // ClusterBuilderSlot中clusterNodeMap全局共享
            return ClusterBuilderSlot.getClusterNode(refResource);
        }

        // 流控模式为"链路"，这个听上去有点懵逼，参考官网案例{@link https://github.com/alibaba/Sentinel/wiki/%E6%B5%81%E9%87%8F%E6%8E%A7%E5%88%B6}
        // 以官网为例，假设两个线程同时访问资源A，那么在资源A上会有两个上下文Entrance1和Entrance2，假设我们只想对某个调用链路进行流控，我们可以设置流控规则为
        // STRATEGY_CHAIN同时设置refResource为Entrance1来表示只有从入口Entrance1的调用才会记录到A的限流统计当中，而不关心经Entrance2的调用
        if (strategy == RuleConstant.STRATEGY_CHAIN) {
            if (!refResource.equals(context.getName())) {
                return null;
            }
            return node;
        }
        // No node.
        return null;
    }

    private static boolean filterOrigin(String origin) {
        // Origin cannot be `default` or `other`.
        return !RuleConstant.LIMIT_APP_DEFAULT.equals(origin) && !RuleConstant.LIMIT_APP_OTHER.equals(origin);
    }


    private static boolean fallbackToLocalOrPass(FlowRule rule, Context context, DefaultNode node, int acquireCount,
                                                 boolean prioritized) {
        if (rule.getClusterConfig().isFallbackToLocalWhenFail()) {
            return passLocalCheck(rule, context, node, acquireCount, prioritized);
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

    private static boolean applyTokenResult(/*@NonNull*/ TokenResult result, FlowRule rule, Context context, DefaultNode node,
                                                 int acquireCount, boolean prioritized) {
        switch (result.getStatus()) {
            case TokenResultStatus.OK:
                return true;
            case TokenResultStatus.SHOULD_WAIT:
                // Wait for next tick.
                try {
                    Thread.sleep(result.getWaitInMs());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            case TokenResultStatus.NO_RULE_EXISTS:
            case TokenResultStatus.BAD_REQUEST:
            case TokenResultStatus.FAIL:
            case TokenResultStatus.TOO_MANY_REQUEST:
                return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
            case TokenResultStatus.BLOCKED:
            default:
                return false;
        }
    }

    private FlowRuleChecker() {}
}