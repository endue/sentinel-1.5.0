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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slots.block.AbstractRule;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;

/**
 * Rules for "hot-spot" frequent parameter flow control.
 * 热点资源规则
 * @author jialiang.linjl
 * @author Eric Zhao
 * @since 0.2.0
 */
public class ParamFlowRule extends AbstractRule {

    public ParamFlowRule() {}

    public ParamFlowRule(String resourceName) {
        setResource(resourceName);
    }

    /**
     * The threshold type of flow control (0: thread count, 1: QPS).
     * 限流模式
     */
    private int grade = RuleConstant.FLOW_GRADE_QPS;

    /**
     * Parameter index.
     * 热点参数的索引，必填，对应 SphU.entry(xxx, args) 中的参数索引位置
     *
     */
    private Integer paramIdx;

    /**
     * The threshold count.
     * 热点参数阈值
     */
    private double count;

    /**
     * Original exclusion items of parameters.
     * 参数例外项，可以针对指定的参数值单独设置限流阈值，不受前面count阈值的限制。仅支持基本类型和字符串类型
     * 如下：
     * ParamFlowRule rule = new ParamFlowRule(resourceName)
     *     .setParamIdx(0)
     *     .setCount(5);
     * // 针对int类型的参数2，单独设置限流QPS阈值为10，而不是全局的阈值5.
     * ParamFlowItem item = new ParamFlowItem().setObject(String.valueOf(PARAM_B))
     *     .setClassType(int.class.getName())
     *     .setCount(10);
     * rule.setParamFlowItemList(Collections.singletonList(item));
     *
     * ParamFlowRuleManager.loadRules(Collections.singletonList(rule));
     */
    private List<ParamFlowItem> paramFlowItemList = new ArrayList<ParamFlowItem>();

    /**
     * Parsed exclusion items of parameters. Only for internal use.
     * 参数排除项，仅供内部使用。
     * key是参数值，value是配置的token阈值
     * 参考用处：{@link ParamFlowChecker#passSingleValueCheck}
     * 该属性其实就是当初始化ParamFlowRule到ParamFlowRuleManager时，解析的paramFlowItemList，
     * 具体可参考ParamFlowRuleManager中的RulePropertyListener处理配置变更的操作流程
     */
    private Map<Object, Integer> hotItems = new HashMap<Object, Integer>();

    /**
     * Indicating whether the rule is for cluster mode.
     */
    private boolean clusterMode = false;
    /**
     * Cluster mode specific config for parameter flow rule.
     * 集群模式配置
     */
    private ParamFlowClusterConfig clusterConfig;

    public int getGrade() {
        return grade;
    }

    public ParamFlowRule setGrade(int grade) {
        this.grade = grade;
        return this;
    }

    public Integer getParamIdx() {
        return paramIdx;
    }

    public ParamFlowRule setParamIdx(Integer paramIdx) {
        this.paramIdx = paramIdx;
        return this;
    }

    public double getCount() {
        return count;
    }

    public ParamFlowRule setCount(double count) {
        this.count = count;
        return this;
    }

    public List<ParamFlowItem> getParamFlowItemList() {
        return paramFlowItemList;
    }

    public ParamFlowRule setParamFlowItemList(List<ParamFlowItem> paramFlowItemList) {
        this.paramFlowItemList = paramFlowItemList;
        return this;
    }

    public Integer retrieveExclusiveItemCount(Object value) {
        if (value == null || hotItems == null) {
            return null;
        }
        return hotItems.get(value);
    }

    Map<Object, Integer> getParsedHotItems() {
        return hotItems;
    }

    ParamFlowRule setParsedHotItems(Map<Object, Integer> hotItems) {
        this.hotItems = hotItems;
        return this;
    }

    public boolean isClusterMode() {
        return clusterMode;
    }

    public ParamFlowRule setClusterMode(boolean clusterMode) {
        this.clusterMode = clusterMode;
        return this;
    }

    public ParamFlowClusterConfig getClusterConfig() {
        return clusterConfig;
    }

    public ParamFlowRule setClusterConfig(ParamFlowClusterConfig clusterConfig) {
        this.clusterConfig = clusterConfig;
        return this;
    }

    @Override
    @Deprecated
    public boolean passCheck(Context context, DefaultNode node, int count, Object... args) {
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        if (!super.equals(o)) { return false; }

        ParamFlowRule rule = (ParamFlowRule)o;

        if (grade != rule.grade) { return false; }
        if (Double.compare(rule.count, count) != 0) { return false; }
        if (clusterMode != rule.clusterMode) { return false; }
        if (paramIdx != null ? !paramIdx.equals(rule.paramIdx) : rule.paramIdx != null) { return false; }
        if (paramFlowItemList != null ? !paramFlowItemList.equals(rule.paramFlowItemList)
            : rule.paramFlowItemList != null) { return false; }
        return clusterConfig != null ? clusterConfig.equals(rule.clusterConfig) : rule.clusterConfig == null;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        long temp;
        result = 31 * result + grade;
        result = 31 * result + (paramIdx != null ? paramIdx.hashCode() : 0);
        temp = Double.doubleToLongBits(count);
        result = 31 * result + (int)(temp ^ (temp >>> 32));
        result = 31 * result + (paramFlowItemList != null ? paramFlowItemList.hashCode() : 0);
        result = 31 * result + (clusterMode ? 1 : 0);
        result = 31 * result + (clusterConfig != null ? clusterConfig.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "ParamFlowRule{" +
            "grade=" + grade +
            ", paramIdx=" + paramIdx +
            ", count=" + count +
            ", paramFlowItemList=" + paramFlowItemList +
            ", clusterMode=" + clusterMode +
            ", clusterConfig=" + clusterConfig +
            '}';
    }
}
