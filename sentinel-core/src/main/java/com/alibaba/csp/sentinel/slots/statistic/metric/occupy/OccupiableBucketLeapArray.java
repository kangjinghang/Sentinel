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
package com.alibaba.csp.sentinel.slots.statistic.metric.occupy;

import com.alibaba.csp.sentinel.slots.statistic.MetricEvent;
import com.alibaba.csp.sentinel.slots.statistic.base.LeapArray;
import com.alibaba.csp.sentinel.slots.statistic.base.WindowWrap;
import com.alibaba.csp.sentinel.slots.statistic.data.MetricBucket;

import java.util.List;

/**
 * @author jialiang.linjl
 * @since 1.5.0
 */
public class OccupiableBucketLeapArray extends LeapArray<MetricBucket> {

    private final FutureBucketLeapArray borrowArray;

    public OccupiableBucketLeapArray(int sampleCount, int intervalInMs) {
        // This class is the original "CombinedBucketArray".
        super(sampleCount, intervalInMs);
        this.borrowArray = new FutureBucketLeapArray(sampleCount, intervalInMs);
    }
    // 在重置的时候，它不是直接重置成 0 的。
    @Override
    public MetricBucket newEmptyBucket(long time) {
        MetricBucket newBucket = new MetricBucket();

        MetricBucket borrowBucket = borrowArray.getWindowValue(time);
        if (borrowBucket != null) {
            newBucket.reset(borrowBucket);
        }

        return newBucket;
    }
    // 在重置的时候，它不是直接重置成 0 的。
    @Override // borrowArray 存储了未来的时间窗口的值。当主线到达某个时间窗口的时候，如果发现当前时间窗口是过期的，前面介绍过，会需要重置这个窗口，这个时候，它会检查一下 borrowArray 是否有值，如果有，将其作为这个窗口的初始值填充进来，而不是简单重置为 0 值。
    protected WindowWrap<MetricBucket> resetWindowTo(WindowWrap<MetricBucket> w, long time) {
        // Update the start time and reset value.
        w.resetTo(time);
        MetricBucket borrowBucket = borrowArray.getWindowValue(time);
        if (borrowBucket != null) {
            w.value().reset();
            w.value().addPass((int)borrowBucket.pass());
        } else {
            w.value().reset();
        }

        return w;
    }

    @Override
    public long currentWaiting() {
        borrowArray.currentWindow();
        long currentWaiting = 0;
        List<MetricBucket> list = borrowArray.values();

        for (MetricBucket window : list) {
            currentWaiting += window.pass();
        }
        return currentWaiting;
    }

    @Override
    public void addWaiting(long time, int acquireCount) {
        WindowWrap<MetricBucket> window = borrowArray.currentWindow(time);
        window.value().add(MetricEvent.PASS, acquireCount);
    }

    @Override
    public void debug(long time) {
        StringBuilder sb = new StringBuilder();
        List<WindowWrap<MetricBucket>> lists = listAll();
        sb.append("a_Thread_").append(Thread.currentThread().getId()).append(" time=").append(time).append("; ");
        for (WindowWrap<MetricBucket> window : lists) {
            sb.append(window.windowStart()).append(":").append(window.value().toString()).append(";");
        }
        sb.append("\n");

        lists = borrowArray.listAll();
        sb.append("b_Thread_").append(Thread.currentThread().getId()).append(" time=").append(time).append("; ");
        for (WindowWrap<MetricBucket> window : lists) {
            sb.append(window.windowStart()).append(":").append(window.value().toString()).append(";");
        }
        System.out.println(sb.toString());
    }
}
