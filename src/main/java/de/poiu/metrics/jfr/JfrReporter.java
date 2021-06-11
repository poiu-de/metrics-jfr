/*
 * Copyright (C) 2020 - 2021 The metrics-jfr Authors
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
package de.poiu.metrics.jfr;

import de.poiu.metrics.jfr.events.TimerEvent;
import de.poiu.metrics.jfr.events.CounterEvent;
import de.poiu.metrics.jfr.events.MeterEvent;
import de.poiu.metrics.jfr.events.GaugeEvent;
import de.poiu.metrics.jfr.events.HistogramEvent;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricAttribute;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jdk.jfr.AnnotationElement;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventFactory;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.ValueDescriptor;

import static com.codahale.metrics.MetricAttribute.COUNT;
import static com.codahale.metrics.MetricAttribute.M15_RATE;
import static com.codahale.metrics.MetricAttribute.M1_RATE;
import static com.codahale.metrics.MetricAttribute.M5_RATE;
import static com.codahale.metrics.MetricAttribute.MAX;
import static com.codahale.metrics.MetricAttribute.MEAN;
import static com.codahale.metrics.MetricAttribute.MEAN_RATE;
import static com.codahale.metrics.MetricAttribute.MIN;
import static com.codahale.metrics.MetricAttribute.P50;
import static com.codahale.metrics.MetricAttribute.P75;
import static com.codahale.metrics.MetricAttribute.P95;
import static com.codahale.metrics.MetricAttribute.P98;
import static com.codahale.metrics.MetricAttribute.P99;
import static com.codahale.metrics.MetricAttribute.P999;
import static com.codahale.metrics.MetricAttribute.STDDEV;

/**
 * A reporter which outputs measurements as JFR Events..
 */
public class JfrReporter extends ScheduledReporter {
    /**
     * Returns a new {@link Builder} for {@link JfrReporter}.
     *
     * @param registry the registry to report
     * @return a {@link Builder} instance for a {@link JfrReporter}
     */
    public static Builder forRegistry(MetricRegistry registry) {
        return new Builder(registry);
    }


    /**
     * A builder for {@link JfrReporter} instances. Defaults to converting rates  to events/seconds
     * durations to milliseconds and not filtering metrics.
     */
    public static class Builder {
        private final MetricRegistry registry;
        private TimeUnit rateUnit;
        private TimeUnit durationUnit;
        private MetricFilter filter;
        private ScheduledExecutorService executor;
        private boolean shutdownExecutorOnStop;
        private Set<MetricAttribute> disabledMetricAttributes;

        private Builder(MetricRegistry registry) {
            this.registry = registry;
            this.rateUnit = TimeUnit.SECONDS;
            this.durationUnit = TimeUnit.MILLISECONDS;
            this.filter = MetricFilter.ALL;
            this.executor = null;
            this.shutdownExecutorOnStop = true;
            disabledMetricAttributes = Collections.emptySet();
        }

        /**
         * Specifies whether or not, the executor (used for reporting) will be stopped with same time with reporter.
         * Default value is true.
         * Setting this parameter to false, has the sense in combining with providing external managed executor via {@link #scheduleOn(ScheduledExecutorService)}.
         *
         * @param shutdownExecutorOnStop if true, then executor will be stopped in same time with this reporter
         * @return {@code this}
         */
        public Builder shutdownExecutorOnStop(boolean shutdownExecutorOnStop) {
            this.shutdownExecutorOnStop = shutdownExecutorOnStop;
            return this;
        }

        /**
         * Specifies the executor to use while scheduling reporting of metrics.
         * Default value is null.
         * Null value leads to executor will be auto created on start.
         *
         * @param executor the executor to use while scheduling reporting of metrics.
         * @return {@code this}
         */
        public Builder scheduleOn(ScheduledExecutorService executor) {
            this.executor = executor;
            return this;
        }

        /**
         * Convert rates to the given time unit.
         *
         * @param rateUnit a unit of time
         * @return {@code this}
         */
        public Builder convertRatesTo(TimeUnit rateUnit) {
            this.rateUnit = rateUnit;
            return this;
        }

        /**
         * Convert durations to the given time unit.
         *
         * @param durationUnit a unit of time
         * @return {@code this}
         */
        public Builder convertDurationsTo(TimeUnit durationUnit) {
            this.durationUnit = durationUnit;
            return this;
        }

        /**
         * Only report metrics which match the given filter.
         *
         * @param filter a {@link MetricFilter}
         * @return {@code this}
         */
        public Builder filter(MetricFilter filter) {
            this.filter = filter;
            return this;
        }

        /**
         * Don't report the passed metric attributes for all metrics (e.g. "p999", "stddev" or "m15").
         * See {@link MetricAttribute}.
         *
         * @param disabledMetricAttributes a {@link MetricFilter}
         * @return {@code this}
         */
        public Builder disabledMetricAttributes(Set<MetricAttribute> disabledMetricAttributes) {
            this.disabledMetricAttributes = disabledMetricAttributes;
            return this;
        }

        /**
         * Builds a {@link JfrReporter} with the given properties.
         *
         * @return a {@link JfrReporter}
         */
        public JfrReporter build() {
            return new JfrReporter(registry,
                    rateUnit,
                    durationUnit,
                    filter,
                    executor,
                    shutdownExecutorOnStop,
                    disabledMetricAttributes);
        }
    }

    private JfrReporter(MetricRegistry registry,
                            TimeUnit rateUnit,
                            TimeUnit durationUnit,
                            MetricFilter filter,
                            ScheduledExecutorService executor,
                            boolean shutdownExecutorOnStop,
                            Set<MetricAttribute> disabledMetricAttributes) {
        super(registry, "jfr-reporter", filter, rateUnit, durationUnit, executor, shutdownExecutorOnStop, disabledMetricAttributes);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void report(SortedMap<String, Gauge> gauges,
                       SortedMap<String, Counter> counters,
                       SortedMap<String, Histogram> histograms,
                       SortedMap<String, Meter> meters,
                       SortedMap<String, Timer> timers) {
        if (!gauges.isEmpty()) {
            for (Map.Entry<String, Gauge> entry : gauges.entrySet()) {
                publishGauge(entry.getKey(), entry.getValue());
            }
        }

        if (!counters.isEmpty()) {
            for (Map.Entry<String, Counter> entry : counters.entrySet()) {
                publishCounter(entry.getKey(), entry);
            }
        }

        if (!histograms.isEmpty()) {
            for (Map.Entry<String, Histogram> entry : histograms.entrySet()) {
                publishHistogram(entry.getKey(), entry.getValue());
            }
        }

        if (!meters.isEmpty()) {
            for (Map.Entry<String, Meter> entry : meters.entrySet()) {
                publishMeter(entry.getKey(), entry.getValue());
            }
        }

        if (!timers.isEmpty()) {
            for (Map.Entry<String, Timer> entry : timers.entrySet()) {
                publishTimer(entry.getKey(), entry.getValue());
            }
        }
    }

    private void publishMeter(String name, Meter meter) {
        final MeterEvent event= new MeterEvent();
        if (event.shouldCommit()) {
          event.name       = name;
          event.rateUnit   = "events/" + getRateUnit();
          if (!getDisabledMetricAttributes().contains(COUNT)) {
            event.count    = meter.getCount();
          }
          if (!getDisabledMetricAttributes().contains(MEAN_RATE)) {
            event.meanRate = convertRate(meter.getMeanRate());
          }
          if (!getDisabledMetricAttributes().contains(M1_RATE)) {
            event.m1Rate   = convertRate(meter.getOneMinuteRate());
          }
          if (!getDisabledMetricAttributes().contains(M5_RATE)) {
            event.m5Rate   = convertRate(meter.getFiveMinuteRate());
          }
          if (!getDisabledMetricAttributes().contains(M15_RATE)) {
            event.m15Rate  = convertRate(meter.getFifteenMinuteRate());
          }
        }
        event.commit();
    }

    private void publishCounter(String name, Map.Entry<String, Counter> entry) {
        final CounterEvent event= new CounterEvent();
        if (event.shouldCommit()) {
          event.name  = name;
          event.value = entry.getValue().getCount();
        }
        event.commit();
    }

    private void publishGauge(String name, Gauge<?> gauge) {
        final GaugeEvent event= new GaugeEvent();
        if (event.shouldCommit()) {
          event.name  = name;
          event.value = gauge.getValue().toString();
        }
        event.commit();
    }

    private void publishHistogram(String name, Histogram histogram) {
        final HistogramEvent event= new HistogramEvent();
        if (event.shouldCommit()) {
          event.name        = name;
          if (!getDisabledMetricAttributes().contains(COUNT)) {
            event.count       = histogram.getCount();
          }
          Snapshot snapshot = histogram.getSnapshot();
          if (!getDisabledMetricAttributes().contains(MIN)) {
            event.min         = snapshot.getMin();
          }
          if (!getDisabledMetricAttributes().contains(MAX)) {
            event.max         = snapshot.getMax();
          }
          if (!getDisabledMetricAttributes().contains(MEAN)) {
            event.mean        = snapshot.getMean();
          }
          if (!getDisabledMetricAttributes().contains(STDDEV)) {
            event.stddev      = snapshot.getStdDev();
          }
          if (!getDisabledMetricAttributes().contains(P50)) {
            event.median      = snapshot.getMedian();
          }
          if (!getDisabledMetricAttributes().contains(P75)) {
            event.p75         = snapshot.get75thPercentile();
          }
          if (!getDisabledMetricAttributes().contains(P95)) {
            event.p95         = snapshot.get95thPercentile();
          }
          if (!getDisabledMetricAttributes().contains(P98)) {
            event.p98         = snapshot.get98thPercentile();
          }
          if (!getDisabledMetricAttributes().contains(P99)) {
            event.p99         = snapshot.get99thPercentile();
          }
          if (!getDisabledMetricAttributes().contains(P999)) {
            event.p999        = snapshot.get999thPercentile();
          }
        }
        event.commit();
    }

    private void publishTimer(String name, Timer timer) {
        final TimerEvent event= new TimerEvent();
        if (event.shouldCommit()) {
          event.name       = name;
          event.rateUnit   = "events/"+getRateUnit();
          if (!getDisabledMetricAttributes().contains(COUNT)) {
            event.count    = timer.getCount();
          }
          if (!getDisabledMetricAttributes().contains(MEAN_RATE)) {
            event.meanRate = convertRate(timer.getMeanRate());
          }
          if (!getDisabledMetricAttributes().contains(M1_RATE)) {
            event.m1Rate   = convertRate(timer.getOneMinuteRate());
          }
          if (!getDisabledMetricAttributes().contains(M5_RATE)) {
            event.m5Rate   = convertRate(timer.getFiveMinuteRate());
          }
          if (!getDisabledMetricAttributes().contains(M15_RATE)) {
            event.m15Rate  = convertRate(timer.getFifteenMinuteRate());
          }
          Snapshot snapshot  = timer.getSnapshot();
          event.durationUnit = getDurationUnit();
          if (!getDisabledMetricAttributes().contains(MIN)) {
            event.min         = convertDuration(snapshot.getMin());
          }
          if (!getDisabledMetricAttributes().contains(MAX)) {
            event.max         = convertDuration(snapshot.getMax());
          }
          if (!getDisabledMetricAttributes().contains(MEAN)) {
            event.mean        = convertDuration(snapshot.getMean());
          }
          if (!getDisabledMetricAttributes().contains(STDDEV)) {
            event.stddev      = convertDuration(snapshot.getStdDev());
          }
          if (!getDisabledMetricAttributes().contains(P50)) {
            event.median      = convertDuration(snapshot.getMedian());
          }
          if (!getDisabledMetricAttributes().contains(P75)) {
            event.p75         = convertDuration(snapshot.get75thPercentile());
          }
          if (!getDisabledMetricAttributes().contains(P95)) {
            event.p95         = convertDuration(snapshot.get95thPercentile());
          }
          if (!getDisabledMetricAttributes().contains(P98)) {
            event.p98         = convertDuration(snapshot.get98thPercentile());
          }
          if (!getDisabledMetricAttributes().contains(P99)) {
            event.p99         = convertDuration(snapshot.get99thPercentile());
          }
          if (!getDisabledMetricAttributes().contains(P999)) {
            event.p999        = convertDuration(snapshot.get999thPercentile());
          }
        }
        event.commit();
    }
}
