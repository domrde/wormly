package wormly

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}

import scala.concurrent.duration._
import scala.language.postfixOps

object GameCycle {
  case object Tick
  case object ManageMe

  def props(): Props = Props(new GameCycle())
}

class GameCycle extends Actor with ActorLogging {
  import GameCycle._

  override def receive: Receive = receiveWithClients(Set.empty)

  context.system.scheduler.schedule(0 millis, 1 second, self, Tick)

  def receiveWithClients(clients: Set[ActorRef]): Receive = {
    case Tick =>
      clients.foreach(_ ! Tick)

    case ManageMe =>
      context.become(receiveWithClients(clients + sender()))
      context.watch(sender())

    case Terminated(client) =>
      context.become(receiveWithClients(clients - client))

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }
}
