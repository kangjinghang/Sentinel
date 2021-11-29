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
package com.alibaba.csp.sentinel;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.context.NullContext;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.slotchain.*;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.Rule;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * {@inheritDoc}
 *
 * @author jialiang.linjl
 * @author leyou(lihao)
 * @author Eric Zhao
 * @see Sph
 */
public class CtSph implements Sph {

    private static final Object[] OBJECTS0 = new Object[0];

    /**
     * Same resource({@link ResourceWrapper#equals(Object)}) will share the same
     * {@link ProcessorSlotChain}, no matter in which {@link Context}.
     */
    private static volatile Map<ResourceWrapper, ProcessorSlotChain> chainMap
        = new HashMap<ResourceWrapper, ProcessorSlotChain>();

    private static final Object LOCK = new Object();

    private AsyncEntry asyncEntryWithNoChain(ResourceWrapper resourceWrapper, Context context) {
        AsyncEntry entry = new AsyncEntry(resourceWrapper, null, context);
        entry.initAsyncContext();
        // The async entry will be removed from current context as soon as it has been created.
        entry.cleanCurrentEntryInLocal();
        return entry;
    }

    private AsyncEntry asyncEntryWithPriorityInternal(ResourceWrapper resourceWrapper, int count, boolean prioritized,
                                                      Object... args) throws BlockException {
        Context context = ContextUtil.getContext();
        if (context instanceof NullContext) {
            // The {@link NullContext} indicates that the amount of context has exceeded the threshold,
            // so here init the entry only. No rule checking will be done.
            return asyncEntryWithNoChain(resourceWrapper, context);
        }
        if (context == null) {
            // Using default context.
            context = InternalContextUtil.internalEnter(Constants.CONTEXT_DEFAULT_NAME);
        }

        // Global switch is turned off, so no rule checking will be done.
        if (!Constants.ON) {
            return asyncEntryWithNoChain(resourceWrapper, context);
        }

        ProcessorSlot<Object> chain = lookProcessChain(resourceWrapper);

        // Means processor cache size exceeds {@link Constants.MAX_SLOT_CHAIN_SIZE}, so no rule checking will be done.
        if (chain == null) {
            return asyncEntryWithNoChain(resourceWrapper, context);
        }

        AsyncEntry asyncEntry = new AsyncEntry(resourceWrapper, chain, context);
        try {
            chain.entry(context, resourceWrapper, null, count, prioritized, args);
            // Initiate the async context only when the entry successfully passed the slot chain.
            asyncEntry.initAsyncContext();
            // The asynchronous call may take time in background, and current context should not be hanged on it.
            // So we need to remove current async entry from current context.
            asyncEntry.cleanCurrentEntryInLocal();
        } catch (BlockException e1) {
            // When blocked, the async entry will be exited on current context.
            // The async context will not be initialized.
            asyncEntry.exitForContext(context, count, args);
            throw e1;
        } catch (Throwable e1) {
            // This should not happen, unless there are errors existing in Sentinel internal.
            // When this happens, async context is not initialized.
            RecordLog.warn("Sentinel unexpected exception in asyncEntryInternal", e1);

            asyncEntry.cleanCurrentEntryInLocal();
        }
        return asyncEntry;
    }

    private AsyncEntry asyncEntryInternal(ResourceWrapper resourceWrapper, int count, Object... args)
        throws BlockException {
        return asyncEntryWithPriorityInternal(resourceWrapper, count, false, args);
    }
    // count默认为1，prioritized默认为false
    private Entry entryWithPriority(ResourceWrapper resourceWrapper, int count, boolean prioritized, Object... args)
        throws BlockException {
        Context context = ContextUtil.getContext(); // 从ThreadLocal中获取context，即一个请求会占用一个线程，一个线程会绑定一个context
        if (context instanceof NullContext) { // 若context是NullContext类型，则表示当前系统中的context数量已经超出了阈值，即访问请求的数量超出了阈值，此时直接返回一个无需做规则检测的资源操作对象
            // The {@link NullContext} indicates that the amount of context has exceeded the threshold,
            // so here init the entry only. No rule checking will be done.
            return new CtEntry(resourceWrapper, null, context);
        }
        // 若当前线程中没有绑定context，则创建一个context并将其放入到ThreadLocal
        if (context == null) {
            // Using default context.
            context = InternalContextUtil.internalEnter(Constants.CONTEXT_DEFAULT_NAME);
        }
        // 若全局开关是关闭的，则直接返回一个无需做规则检测的资源操作对象
        // Global switch is close, no rule checking will do.
        if (!Constants.ON) {
            return new CtEntry(resourceWrapper, null, context);
        }
        // 查找SlotChain
        ProcessorSlot<Object> chain = lookProcessChain(resourceWrapper);

        /*
         * Means amount of resources (slot chain) exceeds {@link Constants.MAX_SLOT_CHAIN_SIZE},
         * so no rule checking will be done.
         */
        if (chain == null) { // 若没有找到chain，则意味着chain数量超出了阈值，则直接返回一个无需做规则检测的资源操作对象
            return new CtEntry(resourceWrapper, null, context);
        }
        // 创建一个资源操作对象。总的来说，就是去一个个链式的去执行slot的方法，每个slot会根据创建的规则，进行自己的逻辑处理，当统计的结果达到设置的阈值时，就会将BlockException抛出到上层，都成功的话就返回一个Entry对象
        Entry e = new CtEntry(resourceWrapper, chain, context);
        try { // 对资源进行操作，执行Slot的entry方法，也就是逐个执行slot的entry方法
            chain.entry(context, resourceWrapper, null, count, prioritized, args);
        } catch (BlockException e1) {
            e.exit(count, args);
            throw e1; // chain的entry方法抛出了BlockException，则将该异常继续向上抛出
        } catch (Throwable e1) {
            // This should not happen, unless there are errors existing in Sentinel internal.
            RecordLog.info("Sentinel unexpected exception", e1);
        }
        return e; // 正常执行了，则最后会将该entry对象返回
    }

    /**
     * Do all {@link Rule}s checking about the resource.
     *
     * <p>Each distinct resource will use a {@link ProcessorSlot} to do rules checking. Same resource will use
     * same {@link ProcessorSlot} globally. </p>
     *
     * <p>Note that total {@link ProcessorSlot} count must not exceed {@link Constants#MAX_SLOT_CHAIN_SIZE},
     * otherwise no rules checking will do. In this condition, all requests will pass directly, with no checking
     * or exception.</p>
     *
     * @param resourceWrapper resource name
     * @param count           tokens needed
     * @param args            arguments of user method call
     * @return {@link Entry} represents this call
     * @throws BlockException if any rule's threshold is exceeded
     */
    public Entry entry(ResourceWrapper resourceWrapper, int count, Object... args) throws BlockException {
        return entryWithPriority(resourceWrapper, count, false, args);
    }

    /**
     * Get {@link ProcessorSlotChain} of the resource. new {@link ProcessorSlotChain} will
     * be created if the resource doesn't relate one.
     * one slot chain per resource，一个资源对应一个{@link ProcessorSlotChain}
     * <p>Same resource({@link ResourceWrapper#equals(Object)}) will share the same
     * {@link ProcessorSlotChain} globally, no matter in which {@link Context}.<p/>
     * 如果是相同的资源（{@link ResourceWrapper#equals(Object)}）会共享一个{@link ProcessorSlotChain}
     * <p>
     * Note that total {@link ProcessorSlot} count must not exceed {@link Constants#MAX_SLOT_CHAIN_SIZE},
     * otherwise null will return.总共的{@link ProcessorSlotChain} 不能超过6000个，也就是说资源不能超过6000个，否则返回null
     * </p>
     *
     * @param resourceWrapper target resource
     * @return {@link ProcessorSlotChain} of the resource
     */
    ProcessorSlot<Object> lookProcessChain(ResourceWrapper resourceWrapper) {
        ProcessorSlotChain chain = chainMap.get(resourceWrapper); // 从缓存map中获取当前资源的SlotChain，缓存map的key是资源，value为其相关的SlotChain
        if (chain == null) { // 双重检测锁DCL，若缓存中没有相关的SlotChain，则创建一个并放入到缓存
            synchronized (LOCK) {
                chain = chainMap.get(resourceWrapper);
                if (chain == null) {
                    // Entry size limit. 如果缓存map的size >= chain数量的最大阈值，则直接返回null，不再创建新的chain
                    if (chainMap.size() >= Constants.MAX_SLOT_CHAIN_SIZE) {
                        return null;
                    }
                    // 创建新的chain（真正的构造方法）
                    chain = SlotChainProvider.newSlotChain();
                    Map<ResourceWrapper, ProcessorSlotChain> newMap = new HashMap<ResourceWrapper, ProcessorSlotChain>(
                        chainMap.size() + 1);
                    newMap.putAll(chainMap);
                    newMap.put(resourceWrapper, chain);
                    chainMap = newMap; // 防止迭代稳定性问题
                }
            }
        }
        return chain;
    }

    /**
     * Get current size of created slot chains.
     *
     * @return size of created slot chains
     * @since 0.2.0
     */
    public static int entrySize() {
        return chainMap.size();
    }

    /**
     * Reset the slot chain map. Only for internal test.
     *
     * @since 0.2.0
     */
    static void resetChainMap() {
        chainMap.clear();
    }

    /**
     * Only for internal test.
     *
     * @since 0.2.0
     */
    static Map<ResourceWrapper, ProcessorSlotChain> getChainMap() {
        return chainMap;
    }

    /**
     * This class is used for skip context name checking.
     */
    private final static class InternalContextUtil extends ContextUtil {
        static Context internalEnter(String name) {
            return trueEnter(name, "");
        }

        static Context internalEnter(String name, String origin) {
            return trueEnter(name, origin);
        }
    }

    @Override
    public Entry entry(String name) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, EntryType.OUT);
        return entry(resource, 1, OBJECTS0);
    }

    @Override
    public Entry entry(Method method) throws BlockException {
        MethodResourceWrapper resource = new MethodResourceWrapper(method, EntryType.OUT);
        return entry(resource, 1, OBJECTS0);
    }

    @Override
    public Entry entry(Method method, EntryType type) throws BlockException {
        MethodResourceWrapper resource = new MethodResourceWrapper(method, type);
        return entry(resource, 1, OBJECTS0);
    }

    @Override
    public Entry entry(String name, EntryType type) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, type);
        return entry(resource, 1, OBJECTS0);
    }

    @Override
    public Entry entry(Method method, EntryType type, int count) throws BlockException {
        MethodResourceWrapper resource = new MethodResourceWrapper(method, type);
        return entry(resource, count, OBJECTS0);
    }

    @Override
    public Entry entry(String name, EntryType type, int count) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, type);
        return entry(resource, count, OBJECTS0);
    }

    @Override
    public Entry entry(Method method, int count) throws BlockException {
        MethodResourceWrapper resource = new MethodResourceWrapper(method, EntryType.OUT);
        return entry(resource, count, OBJECTS0);
    }

    @Override
    public Entry entry(String name, int count) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, EntryType.OUT);
        return entry(resource, count, OBJECTS0);
    }

    @Override
    public Entry entry(Method method, EntryType type, int count, Object... args) throws BlockException {
        MethodResourceWrapper resource = new MethodResourceWrapper(method, type);
        return entry(resource, count, args);
    }

    @Override
    public Entry entry(String name, EntryType type, int count, Object... args) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, type);
        return entry(resource, count, args);
    }

    @Override
    public AsyncEntry asyncEntry(String name, EntryType type, int count, Object... args) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, type);
        return asyncEntryInternal(resource, count, args);
    }

    @Override
    public Entry entryWithPriority(String name, EntryType type, int count, boolean prioritized) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, type);
        return entryWithPriority(resource, count, prioritized);
    }

    @Override
    public Entry entryWithPriority(String name, EntryType type, int count, boolean prioritized, Object... args)
        throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, type);
        return entryWithPriority(resource, count, prioritized, args);
    }

    @Override
    public Entry entryWithType(String name, int resourceType, EntryType entryType, int count, Object[] args)
        throws BlockException { // count参数：表示当前请求可以增加多少个计数
        return entryWithType(name, resourceType, entryType, count, false, args); // 注意：第5个参数为false
    }

    @Override
    public Entry entryWithType(String name, int resourceType, EntryType entryType, int count, boolean prioritized,
                               Object[] args) throws BlockException { // prioritized参 数：true表示当前请求需要等待一定的时间（根据优先级计算后）才能通过，false表示当前请求无需等待
        StringResourceWrapper resource = new StringResourceWrapper(name, entryType, resourceType); // 将信息封装为资源对象
        return entryWithPriority(resource, count, prioritized, args); // 返回一个具有优先级的资源操作对象entry
    }

    @Override
    public AsyncEntry asyncEntryWithType(String name, int resourceType, EntryType entryType, int count,
                                         boolean prioritized, Object[] args) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, entryType, resourceType);
        return asyncEntryWithPriorityInternal(resource, count, prioritized, args);
    }
}
