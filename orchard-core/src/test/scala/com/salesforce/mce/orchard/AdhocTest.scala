package com.salesforce.mce.orchard

import scala.concurrent.duration._

import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.Behavior
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef

object AdhocActor {

  sealed trait Msg
  case class Print(delay: Long) extends Msg
  case class Terminate(replyTo: ActorRef[String]) extends Msg

  def apply(): Behavior[Msg] = {
    Thread.sleep(5000)
    println("Here Here")
    Behaviors.setup(context => new AdhocActor(context))
  }

}

class AdhocActor(context: ActorContext[AdhocActor.Msg]) extends AbstractBehavior(context) {

  import AdhocActor._

  init()

  private def init(): Unit = {
    println("Initializing...")
    Thread.sleep(5000)
    println("Finished initializing")
  }

  override def onMessage(msg: Msg): Behavior[Msg] = msg match {
    case Print(delay) =>
      println(s"sleep $delay...")
      Thread.sleep(delay)
      println("done!")
      this
    case Terminate(replyTo) =>
      println("terminate!")
      replyTo ! "Done"
      Behaviors.stopped
  }

}

class AdhocActorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  import AdhocActor._

  "test" in {

    // val probe = createTestProbe[String]()

    // println("spawn")
    // val adhocActor = spawn(AdhocActor())
    // println("this should be printed before Initializaing and Finished initializing")
    // println("5")
    // adhocActor ! Print(5000)
    // println("4")
    // adhocActor ! Print(4000)
    // println("3")
    // adhocActor ! Print(3000)
    // println("2")
    // adhocActor ! Print(2000)
    // println("1")
    // adhocActor ! Print(1000)
    // println("0")
    // adhocActor ! Terminate(probe.ref)

    // probe.expectMessage(5.minutes, "Done")
  }

}
