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
 * <p>
 * The principle idea comes from Guava. However, the calculation of Guava is
 * rate-based, which means that we need to translate rate to QPS.
 * </p>
 *
 * <p>
 * Requests arriving at the pulse may drag down long idle systems even though it
 * has a much larger handling capability in stable period. It usually happens in
 * scenarios that require extra time for initialization, e.g. DB establishes a connection,
 * connects to a remote service, and so on. That’s why we need “warm up”.
 * </p>
 *
 * <p>
 * Sentinel's "warm-up" implementation is based on the Guava's algorithm.
 * However, Guava’s implementation focuses on adjusting the request interval,
 * which is similar to leaky bucket. Sentinel pays more attention to
 * controlling the count of incoming requests per second without calculating its interval,
 * which resembles token bucket algorithm.
 * </p>
 *
 * <p>
 * The remaining tokens in the bucket is used to measure the system utility.
 * Suppose a system can handle b requests per second. Every second b tokens will
 * be added into the bucket until the bucket is full. And when system processes
 * a request, it takes a token from the bucket. The more tokens left in the
 * bucket, the lower the utilization of the system; when the token in the token
 * bucket is above a certain threshold, we call it in a "saturation" state.
 * </p>
 *
 * <p>
 * Base on Guava’s theory, there is a linear equation we can write this in the
 * form y = m * x + b where y (a.k.a y(x)), or qps(q)), is our expected QPS
 * given a saturated period (e.g. 3 minutes in), m is the rate of change from
 * our cold (minimum) rate to our stable (maximum) rate, x (or q) is the
 * occupied token.
 * </p>
 *
 * @author jialiang.linjl
 */
public class WarmUpController implements TrafficShapingController {

    protected double count;
    private int coldFactor;
    protected int warningToken = 0;
    private int maxToken;
    protected double slope;

    protected AtomicLong storedTokens = new AtomicLong(0);
    protected AtomicLong lastFilledTime = new AtomicLong(0);

    public WarmUpController(double count, int warmUpPeriodInSec, int coldFactor) {
        construct(count, warmUpPeriodInSec, coldFactor);
    }

    public WarmUpController(double count, int warmUpPeriodInSec) {
        construct(count, warmUpPeriodInSec, 3);
    }

    private void construct(double count, int warmUpPeriodInSec, int coldFactor) {

        if (coldFactor <= 1) {
            throw new IllegalArgumentException("Cold factor should be larger than 1");
        }

        this.count = count;

        this.coldFactor = coldFactor;

        // thresholdPermits = 0.5 * warmupPeriod / stableInterval.
        // warningToken = 100;
        warningToken = (int)(warmUpPeriodInSec * count) / (coldFactor - 1);
        // / maxPermits = thresholdPermits + 2 * warmupPeriod /
        // (stableInterval + coldInterval)
        // maxToken = 200
        maxToken = warningToken + (int)(2 * warmUpPeriodInSec * count / (1.0 + coldFactor));

        // slope
        // slope = (coldIntervalMicros - stableIntervalMicros) / (maxPermits
        // - thresholdPermits);
        slope = (coldFactor - 1.0) / count / (maxToken - warningToken);

    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        return canPass(node, acquireCount, false);
    }

    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
        long passQps = (long) node.passQps();

        long previousQps = (long) node.previousPassQps();
        syncToken(previousQps); // 装填令牌桶

        // 开始计算它的斜率
        // 如果进入了警戒线，开始调整他的qps
        long restToken = storedTokens.get();
        if (restToken >= warningToken) {
            long aboveToken = restToken - warningToken;
            // 消耗的速度要比warning快，但是要比慢
            // current interval = restToken*slope+1/count
            // 这里相当于计算了一个在警戒线以上的interval，计算依据可以参考guava的依靠斜率来计算梯形面积部分代码，1.0/count 为恒定速率，有了interval 那么1.0/interval即为这时候期望的QPS
            double warningQps = Math.nextUp(1.0 / (aboveToken * slope + 1.0 / count));
            if (passQps + acquireCount <= warningQps) { //拿这个QPS来做比较
                return true;
            }
        } else {
            if (passQps + acquireCount <= count) { // 如果在警戒线下，就退化为基本的QPS流控
                return true;
            }
        }

        return false;
    }

    protected void syncToken(long passQps) {
        long currentTime = TimeUtil.currentTimeMillis();
        currentTime = currentTime - currentTime % 1000; // 获取当前秒
        long oldLastFillTime = lastFilledTime.get(); // 获取上一次装填令牌桶的时间
        if (currentTime <= oldLastFillTime) { // 如果当前秒数小于等于上次装填时间，则return，表示一秒钟内只需要装填一次
            return;
        }

        long oldValue = storedTokens.get(); // 获取目前令牌桶中的令牌数目
        long newValue = coolDownTokens(currentTime, passQps); // 根据当前时间和前一秒pass的Qps来装填
        // 如果CAS成功，那做下一步操作，就算冲突也无所谓，保证一秒装填一次就行
        if (storedTokens.compareAndSet(oldValue, newValue)) {
            long currentValue = storedTokens.addAndGet(0 - passQps); // 将前一秒的QPS减去，注意点：如果没有前面保证一秒钟内只修改一次的策略，会有多减的可能性
            if (currentValue < 0) { // 不要成为负数
                storedTokens.set(0L);
            }
            lastFilledTime.set(currentTime); // 修改装填时间
        }

    }

    private long coolDownTokens(long currentTime, long passQps) {
        long oldValue = storedTokens.get();
        long newValue = oldValue;

        // 添加令牌的判断前提条件:
        // 当令牌的消耗程度远远低于警戒线的时候，这里将装填的速率恒定为1.0/QPS，那么装填后的值即为oldValue + time / (1000 / count)
        if (oldValue < warningToken) {
            newValue = (long)(oldValue + (currentTime - lastFilledTime.get()) * count / 1000);
        } else if (oldValue > warningToken) {
            if (passQps < (int)count / coldFactor) { // 如果之前的令牌桶数目已经高于警戒线，那么看pass了的QPS是否小于一个阈值，决定是否装填
                newValue = (long)(oldValue + (currentTime - lastFilledTime.get()) * count / 1000);
            }
        }
        return Math.min(newValue, maxToken);
    }

}
