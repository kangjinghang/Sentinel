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
package com.alibaba.csp.sentinel.context;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphO;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.node.EntranceNode;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slots.nodeselector.NodeSelectorSlot;

/**
 * This class holds metadata of current invocation:<br/>
 *
 * <ul>
 * <li>the {@link EntranceNode}: the root of the current invocation
 * tree.</li>
 * <li>the current {@link Entry}: the current invocation point.</li>
 * <li>the current {@link Node}: the statistics related to the
 * {@link Entry}.</li>
 * <li>the origin: The origin is useful when we want to control different
 * invoker/consumer separately. Usually the origin could be the Service Consumer's app name
 * or origin IP. </li>
 * </ul>
 * <p>
 * Each {@link SphU}#entry() or {@link SphO}#entry() should be in a {@link Context},
 * if we don't invoke {@link ContextUtil}#enter() explicitly, DEFAULT context will be used.
 * </p>
 * <p>
 * A invocation tree will be created if we invoke {@link SphU}#entry() multi times in
 * the same context.
 * </p>
 * <p>
 * Same resource in different context will count separately, see {@link NodeSelectorSlot}.
 * </p>
 *
 * @author jialiang.linjl
 * @author leyou(lihao)
 * @author Eric Zhao
 * @see ContextUtil
 * @see NodeSelectorSlot
 */
public class Context { // Sentinel熔断限流执行的上下文，包含资源调用的节点和Entry信息

    /**
     * Context name. 上下文名称，作用是为了区分不同的调用链路
     */
    private final String name;

    /**
     * The entrance node of current invocation tree. 当前调用树的入口节点
     */
    private DefaultNode entranceNode;

    /**
     * Current processing entry. 当前入口，Entry的实现类CtEntry包含类parent和child引用，形成了树状结构
     */
    private Entry curEntry;

    /** 来源，比如服务名称、ip等
     * The origin of this context (usually indicate different invokers, e.g. service consumer name or origin IP).
     */
    private String origin = "";

    private final boolean async;

    /**
     * Create a new async context.
     *
     * @param entranceNode entrance node of the context
     * @param name context name
     * @return the new created context
     * @since 0.2.0
     */
    public static Context newAsyncContext(DefaultNode entranceNode, String name) {
        return new Context(name, entranceNode, true);
    }

    public Context(DefaultNode entranceNode, String name) {
        this(name, entranceNode, false);
    }
    // 初始化的时候，curEntry属性是没有值的
    public Context(String name, DefaultNode entranceNode, boolean async) {
        this.name = name;
        this.entranceNode = entranceNode;
        this.async = async;
    }

    public boolean isAsync() {
        return async;
    }

    public String getName() {
        return name;
    }

    public Node getCurNode() {
        return curEntry == null ? null : curEntry.getCurNode();
    }

    public Context setCurNode(Node node) {
        this.curEntry.setCurNode(node); // 其实是设置了curEntry的curNode，Context本身没有直接引用curNode
        return this;
    }

    public Entry getCurEntry() {
        return curEntry;
    }

    public Context setCurEntry(Entry curEntry) {
        this.curEntry = curEntry;
        return this;
    }

    public String getOrigin() {
        return origin;
    }

    public Context setOrigin(String origin) {
        this.origin = origin;
        return this;
    }

    public double getOriginTotalQps() {
        return getOriginNode() == null ? 0 : getOriginNode().totalQps();
    }

    public double getOriginBlockQps() {
        return getOriginNode() == null ? 0 : getOriginNode().blockQps();
    }

    public double getOriginPassReqQps() {
        return getOriginNode() == null ? 0 : getOriginNode().successQps();
    }

    public double getOriginPassQps() {
        return getOriginNode() == null ? 0 : getOriginNode().passQps();
    }

    public long getOriginTotalRequest() {
        return getOriginNode() == null ? 0 : getOriginNode().totalRequest();
    }

    public long getOriginBlockRequest() {
        return getOriginNode() == null ? 0 : getOriginNode().blockRequest();
    }

    public double getOriginAvgRt() {
        return getOriginNode() == null ? 0 : getOriginNode().avgRt();
    }

    public int getOriginCurThreadNum() {
        return getOriginNode() == null ? 0 : getOriginNode().curThreadNum();
    }

    public DefaultNode getEntranceNode() {
        return entranceNode;
    }

    /**
     * Get the parent {@link Node} of the current.  curEntry 是带有树形关系的，会建立起链表
     * 如果当前节点不为null，则当前节点就为最后一个节点，如果当前节点为null，则为entranceNode。
     * @return the parent node of the current.
     */
    public Node getLastNode() {
        if (curEntry != null && curEntry.getLastNode() != null) {
            return curEntry.getLastNode();
        } else {
            return entranceNode;
        }
    }

    public Node getOriginNode() {
        return curEntry == null ? null : curEntry.getOriginNode();
    }

    @Override
    public String toString() {
        return "Context{" +
            "name='" + name + '\'' +
            ", entranceNode=" + entranceNode +
            ", curEntry=" + curEntry +
            ", origin='" + origin + '\'' +
            ", async=" + async +
            '}';
    }
}
