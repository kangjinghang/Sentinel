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
package com.alibaba.csp.sentinel.slots.block.flow.controller;

import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.node.OccupyTimeoutProperty;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.PriorityWaitException;
import com.alibaba.csp.sentinel.slots.block.flow.TrafficShapingController;
import com.alibaba.csp.sentinel.util.TimeUtil;

/**
 * Default throttling controller (immediately reject strategy).
 *
 * @author jialiang.linjl
 * @author Eric Zhao
 */
public class DefaultController implements TrafficShapingController {

    private static final int DEFAULT_AVG_USED_TOKENS = 0;

    private double count;
    private int grade;

    public DefaultController(double count, int grade) {
        this.count = count;
        this.grade = grade;
    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        return canPass(node, acquireCount, false);
    }
    // 快速失败的流控效果中的通过性判断，它原来是用来满足 prioritized 类型的资源的，我们可以认为这类请求有较高的优先级。如果 QPS 达到阈值，这类资源通常不能用快速失败返回， 而是让它去预占未来的 QPS 容量。
    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
        int curCount = avgUsedTokens(node); // 从 node 中获取当前时间窗口中已经统计的数据
        if (curCount + acquireCount > count) { // 若已经统计的数据与本次请求的数量和 大于 设置的阈值，则返回false，表示没有通过检测，若小于阈值，则返回true，表示通过检测
            if (prioritized && grade == RuleConstant.FLOW_GRADE_QPS) { // 只有设置了 prioritized 的情况才会进入到下面的 if 分支，也就是说，对于一般的场景，被限流了，就快速失败
                long currentTime;
                long waitInMs;
                currentTime = TimeUtil.currentTimeMillis();
                waitInMs = node.tryOccupyNext(currentTime, acquireCount, count); // tryOccupyNext 非常复杂，大意就是说去占有"未来的"令牌，可以看到，下面做了 sleep，为了保证 QPS 不会因为预占而撑大
                if (waitInMs < OccupyTimeoutProperty.getOccupyTimeout()) {
                    node.addWaitingRequest(currentTime + waitInMs, acquireCount); // 就是这里设置了 borrowArray 的值
                    node.addOccupiedPass(acquireCount);
                    sleep(waitInMs);

                    // PriorityWaitException indicates that the request will pass after waiting for {@link @waitInMs}.
                    throw new PriorityWaitException(waitInMs);
                }
            }
            return false; // 表示不能通过
        }
        return true;
    }

    private int avgUsedTokens(Node node) {
        if (node == null) { // 如果没有选择出node，则说明没有做统计工作，直接返回0
            return DEFAULT_AVG_USED_TOKENS;
        } // 若阈值类型为线程数，则直接返回当前的线程数量；若阈值类型为QPS，则返回统计的当前的QPS
        return grade == RuleConstant.FLOW_GRADE_THREAD ? node.curThreadNum() : (int)(node.passQps());
    }

    private void sleep(long timeMillis) {
        try {
            Thread.sleep(timeMillis);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }
}
