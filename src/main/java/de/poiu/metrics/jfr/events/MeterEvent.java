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


@Name("de.poiu.metrics.Meter")
@Label("Meter Event")
@Description("An event reporting dropwizard metrics Meters")
@Category({"Metrics", "Meters"})
@StackTrace(false)
public class MeterEvent extends Event {

  @Label("Metric")
  @Description("The name of the metric.")
  public String name;

  @Label("Count")
  @Description("The number of emitted events.")
  public long count;

  @Label("Mean")
  @Description("The mean rate of events.")
  public double meanRate;

  @Label("M1 Rate")
  @Description("The rate per 1 minute.")
  public double m1Rate;

  @Label("M5 Rate")
  @Description("The rate per 5 minutes.")
  public double m5Rate;

  @Label("M15 Rate")
  @Description("The rate per 15 minutes.")
  public double m15Rate;

  @Label("Unit")
  @Description("The unit of the rates of this meter.")
  public String rateUnit;
}

