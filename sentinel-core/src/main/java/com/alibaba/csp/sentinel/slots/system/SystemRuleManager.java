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
package com.alibaba.csp.sentinel.slots.system;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.concurrent.NamedThreadFactory;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.property.DynamicSentinelProperty;
import com.alibaba.csp.sentinel.property.SentinelProperty;
import com.alibaba.csp.sentinel.property.SimplePropertyListener;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>
 * Sentinel System Rule makes the inbound traffic and capacity meet. It takes
 * average rt, qps, thread count of incoming requests into account. And it also
 * provides a measurement of system's load, but only available on Linux.
 * </p>
 * <p>
 * rt, qps, thread count is easy to understand. If the incoming requests'
 * rt,qps, thread count exceeds its threshold, the requests will be
 * rejected.however, we use a different method to calculate the load.
 * </p>
 * <p>
 * Consider the system as a pipeline，transitions between constraints result in
 * three different regions (traffic-limited, capacity-limited and danger area)
 * with qualitatively different behavior. When there isn’t enough request in
 * flight to fill the pipe, RTprop determines behavior; otherwise, the system
 * capacity dominates. Constraint lines intersect at inflight = Capacity ×
 * RTprop. Since the pipe is full past this point, the inflight –capacity excess
 * creates a queue, which results in the linear dependence of RTT on inflight
 * traffic and an increase in system load.In danger area, system will stop
 * responding.<br/>
 * Referring to BBR algorithm to learn more.
 * </p>
 * <p>
 * Note that {@link SystemRule} only effect on inbound requests, outbound traffic
 * will not limit by {@link SystemRule}
 * </p>
 *
 * @author jialiang.linjl
 * @author leyou
 */
public final class SystemRuleManager {

    private static volatile double highestSystemLoad = Double.MAX_VALUE;
    /**
     * cpu usage, between [0, 1]
     */
    private static volatile double highestCpuUsage = Double.MAX_VALUE;
    private static volatile double qps = Double.MAX_VALUE;
    private static volatile long maxRt = Long.MAX_VALUE;
    private static volatile long maxThread = Long.MAX_VALUE;
    /**
     * mark whether the threshold are set by user.
     */
    private static volatile boolean highestSystemLoadIsSet = false;
    private static volatile boolean highestCpuUsageIsSet = false;
    private static volatile boolean qpsIsSet = false;
    private static volatile boolean maxRtIsSet = false;
    private static volatile boolean maxThreadIsSet = false;

    private static AtomicBoolean checkSystemStatus = new AtomicBoolean(false);

    private static SystemStatusListener statusListener = null;
    private final static SystemPropertyListener listener = new SystemPropertyListener(); // 观察者，当systemRule配置发生变更时，会通知该Listener
    private static SentinelProperty<List<SystemRule>> currentProperty = new DynamicSentinelProperty<List<SystemRule>>();

    @SuppressWarnings("PMD.ThreadPoolCreationRule")
    private final static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
        new NamedThreadFactory("sentinel-system-status-record-task", true));

    static {
        checkSystemStatus.set(false);
        statusListener = new SystemStatusListener();
        scheduler.scheduleAtFixedRate(statusListener, 0, 1, TimeUnit.SECONDS); // 每秒查询一次
        currentProperty.addListener(listener); // 添加观察者
    }

    /**
     * Listen to the {@link SentinelProperty} for {@link SystemRule}s. The property is the source
     * of {@link SystemRule}s. System rules can also be set by {@link #loadRules(List)} directly.
     *
     * @param property the property to listen.
     */
    public static void register2Property(SentinelProperty<List<SystemRule>> property) {
        synchronized (listener) {
            RecordLog.info("[SystemRuleManager] Registering new property to system rule manager");
            currentProperty.removeListener(listener);
            property.addListener(listener);
            currentProperty = property;
        }
    }

    /**
     * Load {@link SystemRule}s, former rules will be replaced.
     *
     * @param rules new rules to load.
     */
    public static void loadRules(List<SystemRule> rules) {
        currentProperty.updateValue(rules);
    }

    /**
     * Get a copy of the rules.
     *
     * @return a new copy of the rules.
     */
    public static List<SystemRule> getRules() {

        List<SystemRule> result = new ArrayList<SystemRule>();
        if (!checkSystemStatus.get()) {
            return result;
        }

        if (highestSystemLoadIsSet) {
            SystemRule loadRule = new SystemRule();
            loadRule.setHighestSystemLoad(highestSystemLoad);
            result.add(loadRule);
        }

        if (highestCpuUsageIsSet) {
            SystemRule rule = new SystemRule();
            rule.setHighestCpuUsage(highestCpuUsage);
            result.add(rule);
        }

        if (maxRtIsSet) {
            SystemRule rtRule = new SystemRule();
            rtRule.setAvgRt(maxRt);
            result.add(rtRule);
        }

        if (maxThreadIsSet) {
            SystemRule threadRule = new SystemRule();
            threadRule.setMaxThread(maxThread);
            result.add(threadRule);
        }

        if (qpsIsSet) {
            SystemRule qpsRule = new SystemRule();
            qpsRule.setQps(qps);
            result.add(qpsRule);
        }

        return result;
    }

    public static double getInboundQpsThreshold() {
        return qps;
    }

    public static long getRtThreshold() {
        return maxRt;
    }

    public static long getMaxThreadThreshold() {
        return maxThread;
    }

    static class SystemPropertyListener extends SimplePropertyListener<List<SystemRule>> {
        // 会先关闭系统检查，当配置修改完成之后，再启用。
        @Override
        public synchronized void configUpdate(List<SystemRule> rules) {
            restoreSetting(); // 恢复到默认状态
            // systemRules = rules;
            if (rules != null && rules.size() >= 1) {
                for (SystemRule rule : rules) {
                    loadSystemConf(rule); // 加载配置
                }
            } else {
                checkSystemStatus.set(false);
            }

            RecordLog.info(String.format("[SystemRuleManager] Current system check status: %s, "
                    + "highestSystemLoad: %e, "
                    + "highestCpuUsage: %e, "
                    + "maxRt: %d, "
                    + "maxThread: %d, "
                    + "maxQps: %e",
                checkSystemStatus.get(),
                highestSystemLoad,
                highestCpuUsage,
                maxRt,
                maxThread,
                qps));
        }
        // 重置配置信息
        protected void restoreSetting() {
            checkSystemStatus.set(false);

            // should restore changes
            highestSystemLoad = Double.MAX_VALUE;
            highestCpuUsage = Double.MAX_VALUE;
            maxRt = Long.MAX_VALUE;
            maxThread = Long.MAX_VALUE;
            qps = Double.MAX_VALUE;

            highestSystemLoadIsSet = false;
            highestCpuUsageIsSet = false;
            maxRtIsSet = false;
            maxThreadIsSet = false;
            qpsIsSet = false;
        }

    }

    public static Boolean getCheckSystemStatus() {
        return checkSystemStatus.get();
    }

    public static double getSystemLoadThreshold() {
        return highestSystemLoad;
    }

    public static double getCpuUsageThreshold() {
        return highestCpuUsage;
    }
    // 修改则判断是否小于默认配置，由于之前已经重新初始化过了，所以如果有修改，肯定会比默认的值小。
    public static void loadSystemConf(SystemRule rule) {
        boolean checkStatus = false;
        // Check if it's valid.

        if (rule.getHighestSystemLoad() >= 0) {
            highestSystemLoad = Math.min(highestSystemLoad, rule.getHighestSystemLoad());
            highestSystemLoadIsSet = true;
            checkStatus = true;
        }

        if (rule.getHighestCpuUsage() >= 0) {
            if (rule.getHighestCpuUsage() > 1) {
                RecordLog.warn(String.format("[SystemRuleManager] Ignoring invalid SystemRule: "
                    + "highestCpuUsage %.3f > 1", rule.getHighestCpuUsage()));
            } else {
                highestCpuUsage = Math.min(highestCpuUsage, rule.getHighestCpuUsage());
                highestCpuUsageIsSet = true;
                checkStatus = true;
            }
        }

        if (rule.getAvgRt() >= 0) {
            maxRt = Math.min(maxRt, rule.getAvgRt());
            maxRtIsSet = true;
            checkStatus = true;
        }
        if (rule.getMaxThread() >= 0) {
            maxThread = Math.min(maxThread, rule.getMaxThread());
            maxThreadIsSet = true;
            checkStatus = true;
        }

        if (rule.getQps() >= 0) {
            qps = Math.min(qps, rule.getQps());
            qpsIsSet = true;
            checkStatus = true;
        }

        checkSystemStatus.set(checkStatus);

    }

    /**
     * Apply {@link SystemRule} to the resource. Only inbound traffic will be checked. 判断是否启用SystemRule
     *
     * @param resourceWrapper the resource.
     * @throws BlockException when any system rule's threshold is exceeded.
     */
    public static void checkSystem(ResourceWrapper resourceWrapper, int count) throws BlockException {
        if (resourceWrapper == null) {
            return;
        }
        // Ensure the checking switch is on. 检查系统状态是否为false，如果为false，则代表不检查。如果不配置SystemRule，则不检查
        if (!checkSystemStatus.get()) {
            return;
        }

        // for inbound traffic only 系统检查状态，只检查外部调内部的接口状态 IN，内部调用外部接口不检查
        if (resourceWrapper.getEntryType() != EntryType.IN) {
            return;
        }

        // total qps 获取当前系统的QPS，根据ClusterNode（静态变量，所有请求共享）的successQps计算 successQps总数/时间 每秒成功的记录
        double currentQps = Constants.ENTRY_NODE == null ? 0.0 : Constants.ENTRY_NODE.passQps();
        if (currentQps + count > qps) { // 当前时间窗口的成功率已经超过指定成功率，则报警
            throw new SystemBlockException(resourceWrapper.getName(), "qps");
        }

        // total thread 总线程数
        int currentThread = Constants.ENTRY_NODE == null ? 0 : Constants.ENTRY_NODE.curThreadNum();
        if (currentThread > maxThread) {
            throw new SystemBlockException(resourceWrapper.getName(), "thread"); // 超过报警
        }
        // 平均响应时长
        double rt = Constants.ENTRY_NODE == null ? 0 : Constants.ENTRY_NODE.avgRt();
        if (rt > maxRt) {
            throw new SystemBlockException(resourceWrapper.getName(), "rt"); // 超过配置的最大响应时长，则报警
        }

        // load. BBR algorithm. 完全按照RT,BBR算法来
        if (highestSystemLoadIsSet && getCurrentSystemAvgLoad() > highestSystemLoad) {
            if (!checkBbr(currentThread)) {
                throw new SystemBlockException(resourceWrapper.getName(), "load");
            }
        }

        // cpu usage CPU使用率超过限制
        if (highestCpuUsageIsSet && getCurrentCpuUsage() > highestCpuUsage) {
            throw new SystemBlockException(resourceWrapper.getName(), "cpu");
        }
    }

    private static boolean checkBbr(int currentThread) {
        if (currentThread > 1 &&
            currentThread > Constants.ENTRY_NODE.maxSuccessQps() * Constants.ENTRY_NODE.minRt() / 1000) {
            return false;
        }
        return true;
    }

    public static double getCurrentSystemAvgLoad() {
        return statusListener.getSystemAverageLoad();
    }

    public static double getCurrentCpuUsage() {
        return statusListener.getCpuUsage();
    }
}
