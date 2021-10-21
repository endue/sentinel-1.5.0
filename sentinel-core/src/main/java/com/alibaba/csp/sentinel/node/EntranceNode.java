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

import com.alibaba.csp.sentinel.CtSph;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.nodeselector.NodeSelectorSlot;

/**
 * <p>
 * A {@link Node} represents the entrance of the invocation tree.
 * </p>
 * <p>
 * One {@link Context} will related to a {@link EntranceNode},
 * which represents the entrance of the invocation tree. New {@link EntranceNode} will be created if
 * current context does't have one. Note that same context name will share same {@link EntranceNode}
 * globally.
 * </p>
 *
 * @author qinan.qn
 * @see ContextUtil
 * @see ContextUtil#enter(String, String)
 * @see NodeSelectorSlot
 *
 * 初始化地点参考{@link CtSph#entryWithPriority}
 * 1. 一个线程对应一个Context，每个Context有一个ContextName
 * 2. 一个ContextName对应一个EntranceNode
 * 说明一个Context会创建一个EntranceNode与其对应(ContextName相同则是同一个EntranceNode)，代表该上下文的入口
 *
 * 参考{@link ContextUtil#trueEnter}
 * 1. 每个EntranceNode都会挂在ROOT节点下面，ROOT是一个EntranceNode节点，且其是全局唯一的，它代表应用的入口
 */
public class EntranceNode extends DefaultNode {

    public EntranceNode(ResourceWrapper id, ClusterNode clusterNode) {
        super(id, clusterNode);
    }

    /* EntranceNode重写了获取数据统计的方法，获取的时候将所有子节点的数据全累加到一起后在返回 */

    @Override
    public double avgRt() {
        double total = 0;
        double totalQps = 0;
        for (Node node : getChildList()) {
            total += node.avgRt() * node.passQps();
            totalQps += node.passQps();
        }
        return total / (totalQps == 0 ? 1 : totalQps);
    }

    @Override
    public double blockQps() {
        double blockQps = 0;
        for (Node node : getChildList()) {
            blockQps += node.blockQps();
        }
        return blockQps;
    }

    @Override
    public long blockRequest() {
        long r = 0;
        for (Node node : getChildList()) {
            r += node.blockRequest();
        }
        return r;
    }

    @Override
    public int curThreadNum() {
        int r = 0;
        for (Node node : getChildList()) {
            r += node.curThreadNum();
        }
        return r;
    }

    @Override
    public double totalQps() {
        double r = 0;
        for (Node node : getChildList()) {
            r += node.totalQps();
        }
        return r;
    }

    @Override
    public double successQps() {
        double r = 0;
        for (Node node : getChildList()) {
            r += node.successQps();
        }
        return r;
    }

    @Override
    public double passQps() {
        double r = 0;
        for (Node node : getChildList()) {
            r += node.passQps();
        }
        return r;
    }

    @Override
    public long totalRequest() {
        long r = 0;
        for (Node node : getChildList()) {
            r += node.totalRequest();
        }
        return r;
    }

    @Override
    public long totalPass() {
        long r = 0;
        for (Node node : getChildList()) {
            r += node.totalPass();
        }
        return r;
    }
}
