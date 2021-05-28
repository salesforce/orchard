/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package services

import javax.inject._

import play.api.Configuration

import com.salesforce.mce.orchard.db.OrchardDatabase
import com.salesforce.mce.orchard.OrchardSettings

@Singleton
class DatabaseService @Inject() (conf: Configuration) {

  val orchardDB = OrchardDatabase(OrchardSettings.withRootConfig(conf.underlying))

}
