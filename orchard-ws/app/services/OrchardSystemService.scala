package services

import javax.inject._

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._

import com.salesforce.mce.orchard.system.OrchardSystem

@Singleton
class OrchardSystemService @Inject() (actorSystem: ActorSystem, databaseService: DatabaseService) {
  val orchard =
    actorSystem.spawn(OrchardSystem.apply(databaseService.orchardDB), "orchard-system")
}
