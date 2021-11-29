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
package com.alibaba.csp.sentinel.slots.statistic;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotEntryCallback;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotExitCallback;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.PriorityWaitException;
import com.alibaba.csp.sentinel.spi.Spi;
import com.alibaba.csp.sentinel.util.TimeUtil;

import java.util.Collection;

/**
 * <p>
 * A processor slot that dedicates to real time statistics.
 * When entering this slot, we need to separately count the following
 * information:
 * <ul>
 * <li>{@link ClusterNode}: total statistics of a cluster node of the resource ID.</li>
 * <li>Origin node: statistics of a cluster node from different callers/origins.</li>
 * <li>{@link DefaultNode}: statistics for specific resource name in the specific context.</li>
 * <li>Finally, the sum statistics of all entrances.</li>
 * </ul>
 * </p>
 *
 * @author jialiang.linjl
 * @author Eric Zhao
 */
@Spi(order = Constants.ORDER_STATISTIC_SLOT)
public class StatisticSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count,
                      boolean prioritized, Object... args) throws Throwable {
        try {
            // Do some checking. 调用SlotChain中后续的所有slot，完成所有规则检测。其在执行过程中可能会抛异常，例如：规则检测未通过，抛出BlockException
            fireEntry(context, resourceWrapper, node, count, prioritized, args);

            // Request passed, add thread count and pass count. 代码能走到这里，说明前面的检测全部通过，此时就可以将该请求统计到相应数据中了
            node.increaseThreadNum(); // 增加线程数
            node.addPassRequest(count); // 增加通过的请求数量
            // 如果在调用entry之前指定了调用的origin，即调用方，则会有一个originNode
            if (context.getCurEntry().getOriginNode() != null) {
                // Add count for origin node. 我们也需要做上面两个增加操作，方便针对调用方的统计，为后续的限流做准备
                context.getCurEntry().getOriginNode().increaseThreadNum();
                context.getCurEntry().getOriginNode().addPassRequest(count);
            }

            if (resourceWrapper.getEntryType() == EntryType.IN) { // 如果是IN，则在Cluster节点上新增线程数和通过请求数，这个是全局的ClusterNode（类的静态变量），和ClusterBuilderSlot的ClusterNode不一样，此处所有请求共享同一个Cluster
                // Add count for global inbound entry node for global statistics.
                Constants.ENTRY_NODE.increaseThreadNum();
                Constants.ENTRY_NODE.addPassRequest(count);
            }

            // Handle pass event with registered entry callback handlers.
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onPass(context, resourceWrapper, node, count, args); // 钩子函数
            }
        } catch (PriorityWaitException ex) {
            node.increaseThreadNum(); // 增加线程数
            if (context.getCurEntry().getOriginNode() != null) {
                // Add count for origin node.
                context.getCurEntry().getOriginNode().increaseThreadNum();
            }

            if (resourceWrapper.getEntryType() == EntryType.IN) { // 增加线程数 共享全局Cluster
                // Add count for global inbound entry node for global statistics.
                Constants.ENTRY_NODE.increaseThreadNum();
            }
            // Handle pass event with registered entry callback handlers.
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onPass(context, resourceWrapper, node, count, args); // 钩子函数
            }
        } catch (BlockException e) {
            // Blocked, set block exception to current entry.
            context.getCurEntry().setBlockError(e);

            // Add block count. 如果触发了BlockException，则说明获取token失败，被限流，因此增加当前秒Block的请求数
            node.increaseBlockQps(count);
            if (context.getCurEntry().getOriginNode() != null) { // 这里是针对调用方origin的统计
                context.getCurEntry().getOriginNode().increaseBlockQps(count);
            }

            if (resourceWrapper.getEntryType() == EntryType.IN) {
                // Add count for global inbound entry node for global statistics.
                Constants.ENTRY_NODE.increaseBlockQps(count);
            }

            // Handle block event with registered entry callback handlers.
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onBlocked(e, context, resourceWrapper, node, count, args);
            }

            throw e;
        } catch (Throwable e) {
            // Unexpected internal error, set error to current entry.
            context.getCurEntry().setError(e);

            throw e;
        }
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        Node node = context.getCurNode();

        if (context.getCurEntry().getBlockError() == null) {
            // Calculate response time (use completeStatTime as the time of completion).
            long completeStatTime = TimeUtil.currentTimeMillis();
            context.getCurEntry().setCompleteTimestamp(completeStatTime);
            long rt = completeStatTime - context.getCurEntry().getCreateTimestamp();  // 获取当前请求的round trip time，即响应时间

            Throwable error = context.getCurEntry().getError();

            // Record response time and success count.
            recordCompleteFor(node, count, rt, error);
            recordCompleteFor(context.getCurEntry().getOriginNode(), count, rt, error);
            if (resourceWrapper.getEntryType() == EntryType.IN) { // 针对所有的入口流量，使用了一个全局的 ENTRY_NODE 进行统计，所以我们也要知道，系统保护规则是全局的，和具体的某个资源没有关系。
                recordCompleteFor(Constants.ENTRY_NODE, count, rt, error);
            }
        }

        // Handle exit event with registered exit callback handlers.
        Collection<ProcessorSlotExitCallback> exitCallbacks = StatisticSlotCallbackRegistry.getExitCallbacks();
        for (ProcessorSlotExitCallback handler : exitCallbacks) {
            handler.onExit(context, resourceWrapper, count, args); // 回调钩子
        }
        // 调用下游的slot exit方法
        fireExit(context, resourceWrapper, count);
    }

    private void recordCompleteFor(Node node, int batchCount, long rt, Throwable error) {
        if (node == null) {
            return;
        }
        node.addRtAndSuccess(rt, batchCount); // 记录全局的round trip time
        node.decreaseThreadNum(); // 线程数减1，减少当前资源的并发线程数

        if (error != null && !(error instanceof BlockException)) {
            node.increaseExceptionQps(batchCount);
        }
    }
}
