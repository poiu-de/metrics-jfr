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
package de.poiu.metrics.jfr.events;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;


@Name("de.poiu.metrics.Histogram")
@Label("Histogram Event")
@Description("An event reporting dropwizard metrics Histogram")
@Category({"Metrics", "Histograms"})
@StackTrace(false)
public class HistogramEvent extends Event {

  @Label("Metric")
  @Description("The name of the metric.")
  public String name;

  @Label("Count of Events")
  @Description("The number of emitted events.")
  public long count;

  @Label("Min")
  @Description("The minimum value.")
  public long min;

  @Label("Max")
  @Description("The maximum value.")
  public long max;

  @Label("Mean")
  @Description("The mean value.")
  public double mean;

  @Label("StdDev")
  @Description("The standard deviation.")
  public double stddev;

  @Label("Median (P50)")
  @Description("The median value (P50).")
  public double median;

  @Label("P75")
  @Description("The 75th percentile.")
  public double p75;

  @Label("P95")
  @Description("The 95th percentile.")
  public double p95;

  @Label("P98")
  @Description("The 98th percentile.")
  public double p98;

  @Label("P99")
  @Description("The 99th percentile.")
  public double p99;

  @Label("P999")
  @Description("The 999th percentile.")
  public double p999;
}