/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

import java.time.Clock

import com.google.inject.AbstractModule
import play.api.libs.concurrent.AkkaGuiceSupport

import services._
import tasks.AuthSettingReloadTask
import utils.{Authorization, AuthorizationSettings}

/**
 * This class is a Guice module that tells Guice how to bind several
 * different types. This Guice module is created when the Play
 * application starts.
 * Play will automatically use any class called `Module` that is in
 * the root package. You can create modules in other locations by
 * adding `play.modules.enabled` settings to the `application.conf`
 * configuration file.
 */
class Module extends AbstractModule with AkkaGuiceSupport {

  override def configure() = {
    // Use the system clock as the default implementation of Clock
    bind(classOf[Clock]).toInstance(Clock.systemDefaultZone)
    // Ask Guice to create an instance of ApplicationTimer when the
    // application starts.
    bind(classOf[ApplicationTimer]).asEagerSingleton()
    // Set AtomicCounter as the implementation for Counter.
    bind(classOf[Counter]).to(classOf[AtomicCounter])
    // Pass in custom implementation with configs for Authorization
    bind(classOf[Authorization]).toInstance(new Authorization(AuthorizationSettings()))
    // Activate authorization setting reload task
    bind(classOf[AuthSettingReloadTask]).asEagerSingleton()
    // Activate metrics
    bind(classOf[Metric]).to(classOf[PrometheusMetric])

    // Startup the orchard system root actor
    bind(classOf[OrchardSystemService]).asEagerSingleton()
  }

}
