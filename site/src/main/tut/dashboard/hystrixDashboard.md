---
layout: docs
title:  "Metrics visualisation"
number: 3
---

## Talos Hystrix Reporter

The Talos hystrix reporter sends the metrics over http in a format compatible with hystrix dashboard.
In the future there may be another dashboard but for now it was the fastest way to 
deliver fine grained visualisations.


### Usage

```scala
libraryDependencies += "org.vaslabs.talos" %% "hystrixreporter" % "0.6.0"
```

Get an akka directive

```tut:silent
import java.time.Clock

import akka.http.scaladsl.server.Route
import akka.actor.ActorSystem

import talos.http.HystrixReporterDirective

def hystrixReporterDirective(implicit actorSystem: ActorSystem, clock: Clock): Route  =
    new HystrixReporterDirective().hystrixStreamHttpRoute
```

So far Talos is opinionated over Akka but the plan is to make it more generic in the future.
