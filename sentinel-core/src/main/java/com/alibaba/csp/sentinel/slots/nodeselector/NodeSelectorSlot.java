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
package com.alibaba.csp.sentinel.slots.nodeselector;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.node.EntranceNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.spi.Spi;

import java.util.HashMap;
import java.util.Map;

/**
 * </p>
 * This class will try to build the calling traces via
 * <ol>
 * <li>adding a new {@link DefaultNode} if needed as the last child in the context.
 * The context's last node is the current node or the parent node of the context. </li>
 * <li>setting itself to the context current node.</li>
 * </ol>
 * </p>
 *
 * <p>It works as follow:</p>
 * <pre>
 * ContextUtil.enter("entrance1", "appA");
 * Entry nodeA = SphU.entry("nodeA");
 * if (nodeA != null) {
 *     nodeA.exit();
 * }
 * ContextUtil.exit();
 * </pre>
 *
 * Above code will generate the following invocation structure in memory:
 *
 * <pre>
 *
 *              machine-root
 *                  /
 *                 /
 *           EntranceNode1
 *               /
 *              /
 *        DefaultNode(nodeA)- - - - - -> ClusterNode(nodeA);
 * </pre>
 *
 * <p>
 * Here the {@link EntranceNode} represents "entrance1" given by
 * {@code ContextUtil.enter("entrance1", "appA")}.
 * </p>
 * <p>
 * Both DefaultNode(nodeA) and ClusterNode(nodeA) holds statistics of "nodeA", which is given
 * by {@code SphU.entry("nodeA")}
 * </p>
 * <p>
 * The {@link ClusterNode} is uniquely identified by the ResourceId; the {@link DefaultNode}
 * is identified by both the resource id and {@link Context}. In other words, one resource
 * id will generate multiple {@link DefaultNode} for each distinct context, but only one
 * {@link ClusterNode}.
 * </p>
 * <p>
 * the following code shows one resource id in two different context:
 * </p>
 *
 * <pre>
 *    ContextUtil.enter("entrance1", "appA");
 *    Entry nodeA = SphU.entry("nodeA");
 *    if (nodeA != null) {
 *        nodeA.exit();
 *    }
 *    ContextUtil.exit();
 *
 *    ContextUtil.enter("entrance2", "appA");
 *    nodeA = SphU.entry("nodeA");
 *    if (nodeA != null) {
 *        nodeA.exit();
 *    }
 *    ContextUtil.exit();
 * </pre>
 *
 * Above code will generate the following invocation structure in memory:
 *
 * <pre>
 *
 *                  machine-root
 *                  /         \
 *                 /           \
 *         EntranceNode1   EntranceNode2
 *               /               \
 *              /                 \
 *      DefaultNode(nodeA)   DefaultNode(nodeA)
 *             |                    |
 *             +- - - - - - - - - - +- - - - - - -> ClusterNode(nodeA);
 * </pre>
 *
 * <p>
 * As we can see, two {@link DefaultNode} are created for "nodeA" in two context, but only one
 * {@link ClusterNode} is created.
 * </p>
 *
 * <p>
 * We can also check this structure by calling: <br/>
 * {@code curl http://localhost:8719/tree?type=root}
 * </p>
 *
 * @author jialiang.linjl
 * @see EntranceNode
 * @see ContextUtil
 */
@Spi(isSingleton = false, order = Constants.ORDER_NODE_SELECTOR_SLOT)
public class NodeSelectorSlot extends AbstractLinkedProcessorSlot<Object> {

    /** 注意：这是成员变量，每new一个NodeSelectorSlot（SPI构造slotChain的时候），就会有一个map。resource:slotChain=1:1
     * {@link DefaultNode}s of the same resource in different context. key就是context的名称 contextName，多个context访问同一个resource，就会对应不的defaultNode
     */
    private volatile Map<String, DefaultNode> map = new HashMap<String, DefaultNode>(10);
    // 责任链实例和 resource name 相关，和线程无关，所以当处理同一个 resource 的时候，会进入到同一个 NodeSelectorSlot 实例中。
    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, Object obj, int count, boolean prioritized, Object... args)
        throws Throwable { // 主要就是要处理：不同的 context name，同一个 resource name 的情况。
        /*
         * It's interesting that we use context name rather resource name as the map key.
         *
         * Remember that same resource({@link ResourceWrapper#equals(Object)}) will share
         * the same {@link ProcessorSlotChain} globally, no matter in which context. So if
         * code goes into {@link #entry(Context, ResourceWrapper, DefaultNode, int, Object...)},
         * the resource name must be same but context name may not.
         *
         * If we use {@link com.alibaba.csp.sentinel.SphU#entry(String resource)} to
         * enter same resource in different context, using context name as map key can
         * distinguish the same resource. In this case, multiple {@link DefaultNode}s will be created
         * of the same resource name, for every distinct context (different context name) each.
         *
         * Consider another question. One resource may have multiple {@link DefaultNode},
         * so what is the fastest way to get total statistics of the same resource?
         * The answer is all {@link DefaultNode}s with same resource name share one
         * {@link ClusterNode}. See {@link ClusterBuilderSlot} for detail.
         */
        DefaultNode node = map.get(context.getName()); // 根据contextName获取DefaultNode，多线程环境下，每个线程都会创建一个context。对于同一个resource来说（相同的resouce对应相同的slotChain，也就是相同的NodeSelectorSlot），如果context的名称也相同，那么获取到的节点就相同
        if (node == null) {
            synchronized (this) {
                node = map.get(context.getName());
                if (node == null) { // 1.获取当前上下文对应的DefaultNode，如果没有的话会为当前的调用新生成一个DefaultNode节点，它的作用是对资源进行各种统计度量以便进行流控
                    // 如果当前「上下文」中没有该节点，则创建一个DefaultNode节点
                    node = new DefaultNode(resourceWrapper, null);
                    HashMap<String, DefaultNode> cacheMap = new HashMap<String, DefaultNode>(map.size());
                    cacheMap.putAll(map);
                    cacheMap.put(context.getName(), node);
                    map = cacheMap;
                    // Build invocation tree ，将当前node作为「上下文」的最后一个节点的子节点添加进去
                    // 如果context的curEntry.parent.curNode为null，则添加到entranceNode中去，否则添加到context的curEntry.parent.curNode中去
                    ((DefaultNode) context.getLastNode()).addChild(node); // 2.将新创建的DefaultNode节点，添加到context中，作为「entranceNode」或者「curEntry.parent.curNode」的子节点
                }

            }
        }
        // 对应 CtEntry.setUpEntryFor() 中的 context.setCurEntry(this)
        // 将该节点设置为「上下文」中的当前节点，实际是将当前节点赋值给context中curEntry的curNode，在Context的getLastNode中会用到在此处设置的curNode
        context.setCurNode(node); // 3.将DefaultNode节点，添加到context中，作为「curEntry」的curNode，其实是设置了curEntry的curNode，Context本身没有直接引用curNode
        fireEntry(context, resourceWrapper, node, count, prioritized, args); // 由此触发下一个节点的entry方法，node（树形结构）会向后面的slot传递
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        fireExit(context, resourceWrapper, count, args);
    }
}
