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
import com.codahale.metrics.Metric;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jdk.jfr.AnnotationElement;
import jdk.jfr.Category;
import jdk.jfr.Description;
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


    private final ConcurrentHashMap<String, EventFactory> eventFactories= new ConcurrentHashMap<>();


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
                publishCounter(entry.getKey(), entry.getValue());
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
        final EventFactory f= this.eventFactories.computeIfAbsent(name, this::createMeterEventFactory);

        final Event event = f.newEvent();

        if (event.shouldCommit()) {
          if (!getDisabledMetricAttributes().contains(COUNT)) {
            event.set(0, meter.getCount());
          }

          event.set(1, "events/" + getRateUnit());
          if (!getDisabledMetricAttributes().contains(MEAN_RATE)) {
            event.set(2, convertRate(meter.getMeanRate()));
          }
          if (!getDisabledMetricAttributes().contains(M1_RATE)) {
            event.set(3, convertRate(meter.getOneMinuteRate()));
          }
          if (!getDisabledMetricAttributes().contains(M5_RATE)) {
            event.set(4, convertRate(meter.getFiveMinuteRate()));
          }
          if (!getDisabledMetricAttributes().contains(M15_RATE)) {
            event.set(5, convertRate(meter.getFifteenMinuteRate()));
          }
        }

        event.commit();
    }

    private void publishCounter(String name, Counter counter) {
        final EventFactory f= this.eventFactories.computeIfAbsent(name, this::createCounterEventFactory);

        final Event event = f.newEvent();

        if (event.shouldCommit()) {
          event.set(0, counter.getCount());
        }

        event.commit();
    }

    private void publishGauge(String name, Gauge<?> gauge) {
        final EventFactory f= this.eventFactories.computeIfAbsent(name, this::createGaugeEventFactory);

        final Event event = f.newEvent();

        if (event.shouldCommit()) {
          event.set(0, gauge.getValue().toString());
        }

        event.commit();
    }

    private void publishHistogram(String name, Histogram histogram) {
        final EventFactory f= this.eventFactories.computeIfAbsent(name, this::createHistogramEventFactory);

        final Event event = f.newEvent();

        if (event.shouldCommit()) {
          if (!getDisabledMetricAttributes().contains(COUNT)) {
            event.set(0, histogram.getCount());
          }

          Snapshot snapshot  = histogram.getSnapshot();
          event.set(1, getDurationUnit());
          if (!getDisabledMetricAttributes().contains(MIN)) {
            event.set(2, convertDuration(snapshot.getMin()));
          }
          if (!getDisabledMetricAttributes().contains(MAX)) {
            event.set(3, convertDuration(snapshot.getMax()));
          }
          if (!getDisabledMetricAttributes().contains(MEAN)) {
            event.set(4, convertDuration(snapshot.getMean()));
          }
          if (!getDisabledMetricAttributes().contains(STDDEV)) {
            event.set(5, convertDuration(snapshot.getStdDev()));
          }
          if (!getDisabledMetricAttributes().contains(P50)) {
            event.set(6, convertDuration(snapshot.getMedian()));
          }
          if (!getDisabledMetricAttributes().contains(P75)) {
            event.set(7, convertDuration(snapshot.get75thPercentile()));
          }
          if (!getDisabledMetricAttributes().contains(P95)) {
            event.set(8, convertDuration(snapshot.get95thPercentile()));
          }
          if (!getDisabledMetricAttributes().contains(P98)) {
            event.set(9, convertDuration(snapshot.get98thPercentile()));
          }
          if (!getDisabledMetricAttributes().contains(P99)) {
            event.set(10, convertDuration(snapshot.get99thPercentile()));
          }
          if (!getDisabledMetricAttributes().contains(P999)) {
            event.set(11, convertDuration(snapshot.get999thPercentile()));
          }
        }

        event.commit();
    }

    private void publishTimer(String name, Timer timer) {
        final EventFactory f= this.eventFactories.computeIfAbsent(name, this::createTimerEventFactory);

        final Event event = f.newEvent();

        if (event.shouldCommit()) {
          if (!getDisabledMetricAttributes().contains(COUNT)) {
            event.set(0, timer.getCount());
          }

          event.set(1, "events/" + getRateUnit());
          if (!getDisabledMetricAttributes().contains(MEAN_RATE)) {
            event.set(2, convertRate(timer.getMeanRate()));
          }
          if (!getDisabledMetricAttributes().contains(M1_RATE)) {
            event.set(3, convertRate(timer.getOneMinuteRate()));
          }
          if (!getDisabledMetricAttributes().contains(M5_RATE)) {
            event.set(4, convertRate(timer.getFiveMinuteRate()));
          }
          if (!getDisabledMetricAttributes().contains(M15_RATE)) {
            event.set(5, convertRate(timer.getFifteenMinuteRate()));
          }

          Snapshot snapshot  = timer.getSnapshot();
          event.set(6, getDurationUnit());
          if (!getDisabledMetricAttributes().contains(MIN)) {
            event.set(7, convertDuration(snapshot.getMin()));
          }
          if (!getDisabledMetricAttributes().contains(MAX)) {
            event.set(8, convertDuration(snapshot.getMax()));
          }
          if (!getDisabledMetricAttributes().contains(MEAN)) {
            event.set(9, convertDuration(snapshot.getMean()));
          }
          if (!getDisabledMetricAttributes().contains(STDDEV)) {
            event.set(10, convertDuration(snapshot.getStdDev()));
          }
          if (!getDisabledMetricAttributes().contains(P50)) {
            event.set(11, convertDuration(snapshot.getMedian()));
          }
          if (!getDisabledMetricAttributes().contains(P75)) {
            event.set(12, convertDuration(snapshot.get75thPercentile()));
          }
          if (!getDisabledMetricAttributes().contains(P95)) {
            event.set(13, convertDuration(snapshot.get95thPercentile()));
          }
          if (!getDisabledMetricAttributes().contains(P98)) {
            event.set(14, convertDuration(snapshot.get98thPercentile()));
          }
          if (!getDisabledMetricAttributes().contains(P99)) {
            event.set(15, convertDuration(snapshot.get99thPercentile()));
          }
          if (!getDisabledMetricAttributes().contains(P999)) {
            event.set(16, convertDuration(snapshot.get999thPercentile()));
          }
        }
        event.commit();
    }


    private EventFactory createCounterEventFactory(final String name) {
      final var eventAnnotations = this.createEventAnnotationsFor(name, Counter.class);

      final var fields = new ArrayList<ValueDescriptor>();
      final var countAnnotation = Collections.singletonList(new AnnotationElement(Label.class, "Count"));
      fields.add(new ValueDescriptor(long.class, "count", countAnnotation));

      return EventFactory.create(eventAnnotations, fields);
    }


    private EventFactory createGaugeEventFactory(final String name) {
      final var eventAnnotations = this.createEventAnnotationsFor(name, Gauge.class);

      final var fields = new ArrayList<ValueDescriptor>();
      final var valueAnnotation = Collections.singletonList(new AnnotationElement(Label.class, "Value"));
      fields.add(new ValueDescriptor(String.class, "value", valueAnnotation));

      return EventFactory.create(eventAnnotations, fields);
    }


    private EventFactory createTimerEventFactory(final String name) {
      final var eventAnnotations = this.createEventAnnotationsFor(name, Timer.class);

      final var fields = new ArrayList<ValueDescriptor>();
      final var valueAnnotation = List.of(
        new AnnotationElement(Label.class, "Count"),
        new AnnotationElement(Description.class, "The number of emitted events."));
      fields.add(new ValueDescriptor(long.class, "count", valueAnnotation));

      final var rateUnitAnnotation = List.of(
        new AnnotationElement(Label.class, "Rate Unit"),
        new AnnotationElement(Description.class, "The unit of the rates in this metric."));
      fields.add(new ValueDescriptor(String.class, "rateUnit", rateUnitAnnotation));
      final var rateMeanAnnotation = List.of(
        new AnnotationElement(Label.class, "Rate Mean"),
        new AnnotationElement(Description.class, "The mean rate of events."));
      fields.add(new ValueDescriptor(double.class, "meanRate", rateMeanAnnotation));
      final var rateM1Annotation = List.of(
        new AnnotationElement(Label.class, "Rate M1"),
        new AnnotationElement(Description.class, "The rate per 1 minute."));
      fields.add(new ValueDescriptor(double.class, "m1Rate", rateM1Annotation));
      final var rateM5Annotation = List.of(
        new AnnotationElement(Label.class, "Rate M5"),
        new AnnotationElement(Description.class, "The rate per 5 minutes."));
      fields.add(new ValueDescriptor(double.class, "m5Rate", rateM5Annotation));
      final var rateM15Annotation = List.of(
        new AnnotationElement(Label.class, "Rate M15"),
        new AnnotationElement(Description.class, "The rate per 15 minutes."));
      fields.add(new ValueDescriptor(double.class, "m15Rate", rateM15Annotation));

      final var durationUnitAnnotation = List.of(
        new AnnotationElement(Label.class, "Duration Unit"),
        new AnnotationElement(Description.class, "The unit of the durtions in this metric."));
      fields.add(new ValueDescriptor(String.class, "durationUnit", durationUnitAnnotation));
      final var minAnnotation = List.of(
        new AnnotationElement(Label.class, "Min"),
        new AnnotationElement(Description.class, "The minimum duration of all events."));
      fields.add(new ValueDescriptor(double.class, "min", minAnnotation));
      final var maxAnnotation = List.of(
        new AnnotationElement(Label.class, "Max"),
        new AnnotationElement(Description.class, "The maximum value of all events."));
      fields.add(new ValueDescriptor(double.class, "max", maxAnnotation));
      final var meanAnnotation = List.of(
        new AnnotationElement(Label.class, "Mean"),
        new AnnotationElement(Description.class, "The mean value of all events."));
      fields.add(new ValueDescriptor(double.class, "mean", meanAnnotation));
      final var stdDevAnnotation = List.of(
        new AnnotationElement(Label.class, "StdDev"),
        new AnnotationElement(Description.class, "The standard deviation."));
      fields.add(new ValueDescriptor(double.class, "stdDev", stdDevAnnotation));
      final var medianAnnotation = List.of(
        new AnnotationElement(Label.class, "Median (P50)"),
        new AnnotationElement(Description.class, "The median value of all events."));
      fields.add(new ValueDescriptor(double.class, "median", medianAnnotation));
      final var p75Annotation = List.of(
        new AnnotationElement(Label.class, "P75"),
        new AnnotationElement(Description.class, "The 75th percentile."));
      fields.add(new ValueDescriptor(double.class, "p75", p75Annotation));
      final var p95Annotation = List.of(
        new AnnotationElement(Label.class, "P95"),
        new AnnotationElement(Description.class, "The 95th percentile."));
      fields.add(new ValueDescriptor(double.class, "p95", p95Annotation));
      final var p98Annotation = List.of(
        new AnnotationElement(Label.class, "P98"),
        new AnnotationElement(Description.class, "The 98th percentile."));
      fields.add(new ValueDescriptor(double.class, "p98", p98Annotation));
      final var p99Annotation = List.of(
        new AnnotationElement(Label.class, "P99"),
        new AnnotationElement(Description.class, "The 99th percentile."));
      fields.add(new ValueDescriptor(double.class, "p99", p99Annotation));
      final var p999Annotation = List.of(
        new AnnotationElement(Label.class, "P999"),
        new AnnotationElement(Description.class, "The 999th percentile."));
      fields.add(new ValueDescriptor(double.class, "p999", p999Annotation));

      return EventFactory.create(eventAnnotations, fields);
    }


    private EventFactory createMeterEventFactory(final String name) {
      final var eventAnnotations = this.createEventAnnotationsFor(name, Meter.class);

      final var fields = new ArrayList<ValueDescriptor>();
      final var valueAnnotation = List.of(
        new AnnotationElement(Label.class, "Count"),
        new AnnotationElement(Description.class, "The number of emitted events."));
      fields.add(new ValueDescriptor(long.class, "count", valueAnnotation));

      final var rateUnitAnnotation = List.of(
        new AnnotationElement(Label.class, "Rate Unit"),
        new AnnotationElement(Description.class, "The unit of the rates in this metric."));
      fields.add(new ValueDescriptor(String.class, "rateUnit", rateUnitAnnotation));
      final var rateMeanAnnotation = List.of(
        new AnnotationElement(Label.class, "Rate Mean"),
        new AnnotationElement(Description.class, "The mean rate of events."));
      fields.add(new ValueDescriptor(double.class, "meanRate", rateMeanAnnotation));
      final var rateM1Annotation = List.of(
        new AnnotationElement(Label.class, "Rate M1"),
        new AnnotationElement(Description.class, "The rate per 1 minute."));
      fields.add(new ValueDescriptor(double.class, "m1Rate", rateM1Annotation));
      final var rateM5Annotation = List.of(
        new AnnotationElement(Label.class, "Rate M5"),
        new AnnotationElement(Description.class, "The rate per 5 minutes."));
      fields.add(new ValueDescriptor(double.class, "m5Rate", rateM5Annotation));
      final var rateM15Annotation = List.of(
        new AnnotationElement(Label.class, "Rate M15"),
        new AnnotationElement(Description.class, "The rate per 15 minutes."));
      fields.add(new ValueDescriptor(double.class, "m15Rate", rateM15Annotation));

      return EventFactory.create(eventAnnotations, fields);
    }


    private EventFactory createHistogramEventFactory(final String name) {
      final var eventAnnotations = this.createEventAnnotationsFor(name, Histogram.class);

      final var fields = new ArrayList<ValueDescriptor>();
      final var valueAnnotation = List.of(
        new AnnotationElement(Label.class, "Count"),
        new AnnotationElement(Description.class, "The number of emitted events."));
      fields.add(new ValueDescriptor(long.class, "count", valueAnnotation));

      final var durationUnitAnnotation = List.of(
        new AnnotationElement(Label.class, "Duration Unit"),
        new AnnotationElement(Description.class, "The unit of the durtions in this metric."));
      fields.add(new ValueDescriptor(String.class, "durationUnit", durationUnitAnnotation));
      final var minAnnotation = List.of(
        new AnnotationElement(Label.class, "Min"),
        new AnnotationElement(Description.class, "The minimum duration of all events."));
      fields.add(new ValueDescriptor(double.class, "min", minAnnotation));
      final var maxAnnotation = List.of(
        new AnnotationElement(Label.class, "Max"),
        new AnnotationElement(Description.class, "The maximum value of all events."));
      fields.add(new ValueDescriptor(double.class, "max", maxAnnotation));
      final var meanAnnotation = List.of(
        new AnnotationElement(Label.class, "Mean"),
        new AnnotationElement(Description.class, "The mean value of all events."));
      fields.add(new ValueDescriptor(double.class, "mean", meanAnnotation));
      final var stdDevAnnotation = List.of(
        new AnnotationElement(Label.class, "StdDev"),
        new AnnotationElement(Description.class, "The standard deviation."));
      fields.add(new ValueDescriptor(double.class, "stdDev", stdDevAnnotation));
      final var medianAnnotation = List.of(
        new AnnotationElement(Label.class, "Median (P50)"),
        new AnnotationElement(Description.class, "The median value of all events."));
      fields.add(new ValueDescriptor(double.class, "median", medianAnnotation));
      final var p75Annotation = List.of(
        new AnnotationElement(Label.class, "P75"),
        new AnnotationElement(Description.class, "The 75th percentile."));
      fields.add(new ValueDescriptor(double.class, "p75", p75Annotation));
      final var p95Annotation = List.of(
        new AnnotationElement(Label.class, "P95"),
        new AnnotationElement(Description.class, "The 95th percentile."));
      fields.add(new ValueDescriptor(double.class, "p95", p95Annotation));
      final var p98Annotation = List.of(
        new AnnotationElement(Label.class, "P98"),
        new AnnotationElement(Description.class, "The 98th percentile."));
      fields.add(new ValueDescriptor(double.class, "p98", p98Annotation));
      final var p99Annotation = List.of(
        new AnnotationElement(Label.class, "P99"),
        new AnnotationElement(Description.class, "The 99th percentile."));
      fields.add(new ValueDescriptor(double.class, "p99", p99Annotation));
      final var p999Annotation = List.of(
        new AnnotationElement(Label.class, "P999"),
        new AnnotationElement(Description.class, "The 999th percentile."));
      fields.add(new ValueDescriptor(double.class, "p999", p999Annotation));

      return EventFactory.create(eventAnnotations, fields);
    }


    private List<AnnotationElement> createEventAnnotationsFor(final String name, final Class<? extends Metric> metricsType) {
      final String metricsTypeName= metricsType.getSimpleName();
      final String[] category = { "Metrics", metricsTypeName };
      final var eventAnnotations = new ArrayList<AnnotationElement>();
      eventAnnotations.add(new AnnotationElement(Name.class, "de.poiu.metrics." + sanitizeAsClassName(name)));
      eventAnnotations.add(new AnnotationElement(Label.class, name));
      eventAnnotations.add(new AnnotationElement(Description.class, "An event reporting dropwizard metrics " + metricsTypeName + " " + name));
      eventAnnotations.add(new AnnotationElement(Category.class, category));
      eventAnnotations.add(new AnnotationElement(StackTrace.class, false));

      return eventAnnotations;
    }


    private static String sanitizeAsClassName(final String str) {
      final StringBuilder sb = new StringBuilder();
      for (int i = 0; i < str.length(); i++) {
        if ((i == 0 && Character.isJavaIdentifierStart(str.charAt(i))) || (i > 0 && Character.isJavaIdentifierPart(str.charAt(i))))
          sb.append(str.charAt(i));
        else
          sb.append((int)str.charAt(i));
      }

      sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
      return sb.toString();
    }
}
