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
import com.alibaba.csp.sentinel.slots.block.flow.TrafficShapingController;
import com.alibaba.csp.sentinel.util.TimeUtil;

import java.util.concurrent.atomic.AtomicLong;

/**
 * @author jialiang.linjl 漏桶算法来完成匀速器的功能，主要是做到了一个流量整形的功能。用队列来实现
 */
public class RateLimiterController implements TrafficShapingController {

    private final int maxQueueingTimeMs;
    private final double count;

    private final AtomicLong latestPassedTime = new AtomicLong(-1);

    public RateLimiterController(int timeOut, double count) {
        this.maxQueueingTimeMs = timeOut;
        this.count = count;
    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        return canPass(node, acquireCount, false);
    }

    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
        // Pass when acquire count is less or equal than 0.
        if (acquireCount <= 0) {
            return true;
        }
        // Reject when count is less or equal than 0.
        // Otherwise,the costTime will be max of long and waitTime will overflow in some cases.
        if (count <= 0) {
            return false;
        }
        // 按照斜率来计算计划中应该什么时候通过，这里获取当前时间
        long currentTime = TimeUtil.currentTimeMillis();
        // Calculate the interval between every two requests. 获取acquireCount个令牌需要的时间
        long costTime = Math.round(1.0 * (acquireCount) / count * 1000);

        // Expected pass time of this request. 期待时间，上次pass的时间加上这次获取需要花费的时间就得到了期望的时间
        long expectedTime = costTime + latestPassedTime.get();

        if (expectedTime <= currentTime) { // 如果期望时间小于等于当前时间，说明现在立马就能够获得令牌
            // Contention may exist here, but it's okay. //这里会有冲突,然而冲突就冲突吧.
            latestPassedTime.set(currentTime);
            return true;
        } else { // 如果期望时间大于当前时间
            // Calculate the time to wait. 计算自己需要的等待时间
            long waitTime = costTime + latestPassedTime.get() - TimeUtil.currentTimeMillis();
            if (waitTime > maxQueueingTimeMs) { // 如果等待的时间大于等于最大的队列等待时
                return false; // 返回false标明这一次需要被限流
            } else { // 这里应用了一个类似于double check的做法，先调用latestPassedTime这个AtomicLong的原子加并获取原子加后的新值
                long oldTime = latestPassedTime.addAndGet(costTime);
                try { // 再做一次判断，如果这时候有并发的原子加操作，那么总有线程得到的oldTime与之前期望的不一致，那么在计算一次waitTime
                    waitTime = oldTime - TimeUtil.currentTimeMillis();
                    if (waitTime > maxQueueingTimeMs) { // 如果这时候waitTime超过了最长队列等待时间
                        latestPassedTime.addAndGet(-costTime); // 把加上去的时间又给减回去，并且返回false，触发限流
                        return false;
                    }
                    // in race condition waitTime may <= 0 如果到达了这里，恭喜你，你已经通过了匀速器的考验
                    if (waitTime > 0) { // 现在需要做的就是等待该等待的时间，然后返回true，完成匀速器的使命
                        Thread.sleep(waitTime); // sleep ，并不真的是一个队列
                    }
                    return true;
                } catch (InterruptedException e) {
                }
            }
        }
        return false;
    }

}
