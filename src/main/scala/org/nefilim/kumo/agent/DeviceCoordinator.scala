package org.nefilim.kumo.agent

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import com.typesafe.scalalogging.LazyLogging
import org.nefilim.kumo.agent.Server.KumoAgentConfig

object DeviceCoordinator extends LazyLogging {
  sealed trait Command

  final case class HandleKumoStatus(status: KumoDevice.Status, sender: ActorRef[KumoDevice.Command]) extends Command

  def apply(kumoAgentConfig: KumoAgentConfig): Behavior[Command] = {
    logger.info(s"Creating Behavior for DeviceCoordinator with agent configuration: \n ${kumoAgentConfig}")
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new DeviceCoordinator(kumoAgentConfig, context, timers).run()
      }
    }
  }
}

class DeviceCoordinator private (
    kumoAgentConfig: KumoAgentConfig,
    context: ActorContext[DeviceCoordinator.Command],
    timers: TimerScheduler[DeviceCoordinator.Command]) {

  import DeviceCoordinator._

  context.setLoggerName(this.getClass.getName)

  val devices = kumoAgentConfig.devices.map { d =>
    val deviceActor = context.spawn(KumoDevice(d.ip, d.statusKey, context.self), s"kumo-device-${d.ip}")
    (deviceActor, d.mqttStateTopic)
  }.toMap
  val mqttPublisher = context.spawn(MQTTPublisher(kumoAgentConfig.mqtt.broker), s"MQTTPublisher-${kumoAgentConfig.mqtt.broker}")

  private def run(): Behavior[Command] = {
    Behaviors.receiveMessage {
      case HandleKumoStatus(status, sender) =>
        context.log.info(s"received updated status [$status] from device ${mqttPublisher}")
        devices.get(sender).foreach { topic =>
          mqttPublisher ! MQTTPublisher.PublishString(if (status.isOn) "ON" else "OFF", topic)
        }
        Behaviors.same
    }
  }
}