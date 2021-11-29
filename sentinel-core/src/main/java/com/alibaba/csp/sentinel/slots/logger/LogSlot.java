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
package com.alibaba.csp.sentinel.slots.logger;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.spi.Spi;

/**
 * A {@link com.alibaba.csp.sentinel.slotchain.ProcessorSlot} that is response for logging block exceptions
 * to provide concrete logs for troubleshooting.
 */
@Spi(order = Constants.ORDER_LOG_SLOT)
public class LogSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode obj, int count, boolean prioritized, Object... args)
        throws Throwable {
        try { // 直接 fire 出去了，也就是说，先处理责任链上后面的那些节点，如果它们抛出了 BlockException，那么这里才做处理。
            fireEntry(context, resourceWrapper, obj, count, prioritized, args);
        } catch (BlockException e) {
            EagleEyeLogUtil.log(resourceWrapper.getName(), e.getClass().getSimpleName(), e.getRuleLimitApp(),
                context.getOrigin(), count); // 调用了 EagleEyeLogUtil#log 方法，它其实就是，将被设置的规则 block 的信息记录到日志文件 sentinel-block.log 中。也就是记录哪些接口被规则挡住了。
            throw e;
        } catch (Throwable e) {
            RecordLog.warn("Unexpected entry exception", e);
        }

    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        try {
            fireExit(context, resourceWrapper, count, args);
        } catch (Throwable e) {
            RecordLog.warn("Unexpected entry exit exception", e);
        }
    }
}
