package com.salesforce.mce.orchard

import scala.concurrent.duration._

import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

object SelfMessage {

  val TimeOut = 1.second

  sealed trait Msg
  case class CheckProgress(id: Int) extends Msg

  def apply(supervisor: ActorRef[Int]): Behavior[Msg] = Behaviors.setup { context =>
    Behaviors.withTimers { timers =>
      timers.startSingleTimer("1", CheckProgress(0), TimeOut)
      timers.startSingleTimer("2", CheckProgress(0), TimeOut)
      timers.startSingleTimer("3", CheckProgress(0), TimeOut)
      timers.startSingleTimer("4", CheckProgress(0), TimeOut)
      timers.startSingleTimer("5", CheckProgress(0), TimeOut)
      running(supervisor, timers, 1)
    }
  }

  def running(supervisor: ActorRef[Int], timers: TimerScheduler[Msg], id: Int): Behavior[Msg] = {
    println(s"running with id: $id")
    Behaviors.receiveMessage { case CheckProgress(pid) =>
      println(s"received check progress $pid")
      if (id >= 10) {
        supervisor ! 0
        Behaviors.stopped
      } else {
        timers.startSingleTimer(CheckProgress(id), TimeOut)
        Thread.sleep(4.seconds.toMillis)
        println("new timer")
        running(supervisor, timers, id + 1)
      }
    }
  }

}

class SelfMessageSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  // "test" in {
  //   val probe = createTestProbe[Int]()

  //   spawn(SelfMessage.apply(probe.ref))

  //   probe.expectMessage(1.minute, 0)
  // }

}

object TestFSM {

  sealed trait Msg
  case class Done(idx: Int) extends Msg

  def apply2(elements: Seq[Int]): Behavior[Msg] = {
    println(s"elemtns: ${elements.size}")
    Thread.sleep(10)

    Behaviors.receive {
      case (ctx, Done(10)) =>
        elements.toSeq.sorted.foreach(println)
        Behaviors.stopped
      case (ctx, Done(0)) =>
        println("received 0!")
        apply2(elements :+ (-elements.max))
      case (ctx, Done(idx)) =>
        ctx.self ! Done(0)
        apply2(elements :+ idx)
    }
  }

  def apply(supervisor: ActorRef[Int]): Behavior[Msg] = Behaviors.setup { ctx =>
    val actors = (1 to 10).map { idx =>
      println("spawning!")
      Thread.sleep(10)
      idx -> ctx.spawnAnonymous(TestExecActor(idx, ctx.self))
    }.toMap

    listening(supervisor, actors, ctx)
  }

  def listening(
    supervisor: ActorRef[Int],
    actorMap: Map[Int, ActorRef[TestExecActor.Msg]],
    ctx: ActorContext[Msg]
  ): Behavior[Msg] = {
    Behaviors.receiveMessage { case Done(idx) =>
      println(s"received Done($idx)")
      println(actorMap.size)
      val newMap = actorMap - idx
      if (newMap.nonEmpty) {
        // Thread.sleep(50)
        listening(supervisor, newMap, ctx)
      } else {
        supervisor ! 0
        Behaviors.stopped
      }
    }
  }

}

object TestExecActor {

  sealed trait Msg

  def apply(id: Int, replyTo: ActorRef[TestFSM.Msg]): Behavior[Msg] = Behaviors.setup { ctx =>
    Thread.sleep(10)
    replyTo ! TestFSM.Done(id)
    Behaviors.stopped
  }

}

class TestFSMSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  // "test" in {
  //   val probe = createTestProbe[Int]()
  //   println("hello")
  //   spawn(TestFSM(probe.ref))

  //   probe.expectMessage(1.minute, 0)
  // }

  // "test2" in {
  //   val probe = createTestProbe[Int]()

  //   val fsm = spawn(TestFSM.apply2(Seq.empty))
  //   (1 to 9).foreach { i =>
  //     Thread.sleep(50)
  //     fsm ! TestFSM.Done(i)
  //   }

  //   Thread.sleep(1000)
  //   fsm ! TestFSM.Done(10)

  //   probe.expectTerminated(fsm, 1.minute)
  // }

}
