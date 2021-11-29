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

import com.alibaba.csp.sentinel.slots.statistic.base.LeapArray;
import com.alibaba.csp.sentinel.slots.statistic.base.WindowWrap;
import com.alibaba.csp.sentinel.slots.statistic.data.MetricBucket;

/**
 * A kind of {@code BucketLeapArray} that only reserves for future buckets.
 *
 * @author jialiang.linjl
 * @since 1.5.0
 */
public class FutureBucketLeapArray extends LeapArray<MetricBucket> {

    public FutureBucketLeapArray(int sampleCount, int intervalInMs) {
        // This class is the original "BorrowBucketArray".
        super(sampleCount, intervalInMs);
    }

    @Override
    public MetricBucket newEmptyBucket(long time) {
        return new MetricBucket();
    }

    @Override
    protected WindowWrap<MetricBucket> resetWindowTo(WindowWrap<MetricBucket> w, long startTime) {
        // Update the start time and reset value.
        w.resetTo(startTime);
        w.value().reset();
        return w;
    }

    @Override
    public boolean isWindowDeprecated(long time, WindowWrap<MetricBucket> windowWrap) {
        // Tricky: will only calculate for future. 在调用 values() 方法的时候，如果所有的2个窗口都是过期的，将得不到任何的值。所以，我们大概可以判断，给这个数组添加值的时候，使用的时间应该不是当前时间，而是一个未来的时间点。这大概就是 Future 要表达的意思。
        return time >= windowWrap.windowStart();
    }
}
