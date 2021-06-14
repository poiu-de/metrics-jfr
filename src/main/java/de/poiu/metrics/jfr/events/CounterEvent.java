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


@Name("de.poiu.metrics.Counter")
@Label("Counter Event")
@Description("An event reporting dropwizard metrics Counters")
@Category({"Metrics", "Counters"})
@StackTrace(false)
public class CounterEvent extends Event {

  @Label("Metric")
  @Description("The name of the metric.")
  public String name;

  @Label("Count")
  @Description("The value of the Counter.")
  public long value;
}
