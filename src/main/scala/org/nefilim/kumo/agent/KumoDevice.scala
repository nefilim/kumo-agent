package org.nefilim.kumo.agent

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import com.typesafe.scalalogging.LazyLogging
import org.json4s._
import org.json4s.jackson.Serialization
import org.json4s.jackson.JsonMethods._
import sttp.client.okhttp.OkHttpFutureBackend
import sttp.client._
import sttp.model.StatusCode

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object KumoDevice extends LazyLogging {
  sealed trait Command
  sealed trait Result

  private final case object PollStatus extends Command

  final private case class PollStatusResult(status: InternalStatus) extends Command
  final private case class PollStatusFailure(t: Throwable) extends Command
  final case class GetStatus(replyTo: ActorRef[Result]) extends Command
  final case class UpdateHeatSetpoint(spHeat: Double, replyTo: ActorRef[Result]) extends Command
  final case object TurnOn extends Command
  final case object TurnOff extends Command
  final case class CurrentStatus(status: Status) extends Result

  sealed trait StatusMode
  case object Off extends StatusMode
  case object Heating extends StatusMode
  case object Cooling extends StatusMode
  object StatusMode {
    def parseMode(m: String): StatusMode = {
      m.toLowerCase match {
        case "heat" => Heating
        case "cool" => Cooling
        case "off" => Off
      }
    }
  }

  final case class Status(
    mode: StatusMode,
    currentTemperature: Double,
    setpointHeat: Double,
    setpointCool: Double,
    defrosting: Boolean
  ) {
    def isOn: Boolean = mode == Heating || mode == Cooling
  }

  private case class InternalStatus(
    roomTemp: Double,
    mode: String,
    spCool: Double,
    spHeat: Double,
    vaneDir: String,
    fanSpeed: String,
    tempSource: String,
    activeThermistor: String,
    filterDirty: Boolean,
    hotAdjust: Boolean,
    defrost: Boolean,
    standby: Boolean,
    runTest: Int
  ) {
    def toStatus = Status(StatusMode.parseMode(mode), roomTemp, spHeat, spCool, defrost)
  }

  def apply(ip: String, statusKey: String, coordinator: ActorRef[DeviceCoordinator.Command]): Behavior[Command] = {
    logger.info(s"Creating Behavior for KumoDevice at ${ip}")
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new KumoDevice(ip, statusKey, coordinator, context, timers).start()
      }
    }
  }
}

class KumoDevice private (
    ip: String,
    statusKey: String,
    coordinator: ActorRef[DeviceCoordinator.Command],
    context: ActorContext[KumoDevice.Command],
    timers: TimerScheduler[KumoDevice.Command]) {

  import KumoDevice._

  private def start(): Behavior[Command] = {
    timers.startTimerAtFixedRate(PollStatus, 10.seconds)
    context.self ! PollStatus
    run(None)
  }

  implicit val ec = context.system.executionContext

  private def run(status: Option[InternalStatus]): Behavior[Command] = {
    context.log.debug(s"status is now ${status}")
    Behaviors.receiveMessage {
      case PollStatus =>
        context.log.debug(s"Polling device at ${ip}!")
        context.pipeToSelf(getStatusFromDevice()) { _.flatten match {
            case Success(s) => PollStatusResult(s)
            case Failure(f) => PollStatusFailure(f)
          }
        }
        Behaviors.same
      case PollStatusFailure(t) =>
        context.log.warn(s"failed to get Kumo status: [$t]")
        Behaviors.same
      case PollStatusResult(newStatus) =>
        if (!status.contains(newStatus)) {
          context.log.info(s"sending updated status ${newStatus.toStatus}")
          coordinator ! DeviceCoordinator.HandleKumoStatus(newStatus.toStatus, context.self)
        }
        run(Some(newStatus))
      case GetStatus(replyTo) if status.isDefined =>
        context.log.debug(s"Getting status for device at ${ip}, internal status: $status status: ${status.get.toStatus}")
        replyTo ! CurrentStatus(status.get.toStatus)
        Behaviors.same
    }
  }

  implicit val backend = OkHttpFutureBackend()
  implicit val formats = Serialization.formats(NoTypeHints)

  // context.pipeToSelf expects a function Try[T] => T, so much easier if we produce a Try here
  private def getStatusFromDevice(): Future[Try[InternalStatus]] = {
    Try(basicRequest
      .body("""{"c":{"indoorUnit":{"status":{}}}}""")
      .contentType("application/json")
      .put(uri"http://$ip/api?m=$statusKey")
      .send()) match {

      case Success(f) =>
        f.map { r =>
          r.body match {
            case Right(b) if r.code == StatusCode.Ok =>
              Try {
                val json = parse(b) \ "r" \ "indoorUnit" \ "status"
                context.log.debug(s"JSON ${pretty(render(json))}")
                json.extract[InternalStatus]
              }.recoverWith {
                case t => Failure(new Exception(s"failed to parse json [${b}]", t))
              }
            case Right(_) =>
              Failure(new Exception(s"received a HTTP status code other than Ok: ${r.code}, ignoring data"))
            case Left(e) =>
              Failure(new Exception(e))
          }
        }
      case Failure(f) =>
        Future.successful(Failure(f))
    }
  }
}