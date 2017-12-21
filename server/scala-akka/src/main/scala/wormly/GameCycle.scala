package wormly

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import wormly.Boot.system

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps

object GameCycle {
  case object Tick
  case object ManageMe
  case object UnmanageMe

  def props(): Props = Props(new GameCycle())
}

class GameCycle extends Actor with ActorLogging {
  import GameCycle._

  override def receive: Receive = receiveWithClients(Set.empty)
  private val config = context.system.settings.config
  private val timeout = config.getInt("application.game-cycle-timeout-millis")

  implicit val executionContext: ExecutionContextExecutor = context.system.dispatcher
  context.system.scheduler.schedule(0 millis, timeout millis, self, Tick)

  def receiveWithClients(clients: Set[ActorRef]): Receive = {
    case Tick =>
      clients.foreach(_ ! Tick)

    case ManageMe =>
      context.become(receiveWithClients(clients + sender()))
      context.watch(sender())

    case UnmanageMe =>
      context.become(receiveWithClients(clients - sender()))
      context.unwatch(sender())

    case Terminated(client) =>
      context.become(receiveWithClients(clients - client))
      context.unwatch(client)

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }
}
