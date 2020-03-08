package org.nefilim.kumo.agent

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import com.typesafe.scalalogging.LazyLogging
import org.nefilim.kumo.agent.Server.KumoAgentConfig

object DeviceCoordinator extends LazyLogging {
  sealed trait Command

  final case class HandleKumoStatus(status: KumoDevice.Status, sender: ActorRef[KumoDevice.Command]) extends Command
  final case class KumoDeviceCommand(topic: String, command: String) extends Command

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

  val devicesToStateTopics = kumoAgentConfig.devices.map { d =>
    val deviceActor = context.spawn(KumoDevice(d.ip, d.apiConfig, context.self), s"kumo-device-${d.ip}")
    (deviceActor, d.mqtt.stateTopic)
  }.toMap
  val stateTopicsToDevices = devicesToStateTopics.map { case (k, v) => (v, k) }
  val commandTopicsToStateTopics = kumoAgentConfig.devices.map { d =>
    (d.mqtt.commandTopic, d.mqtt.stateTopic)
  }.toMap
  val mqttPublisher = context.spawn(MQTTPublisher(context.self, commandTopicsToStateTopics.keySet, kumoAgentConfig.mqtt.broker), s"MQTTPublisher-${kumoAgentConfig.mqtt.broker}")

  private def run(): Behavior[Command] = {
    Behaviors.receiveMessage {
      case HandleKumoStatus(status, sender) =>
        context.log.info(s"received updated status [$status] from device ${mqttPublisher}")
        devicesToStateTopics.get(sender).foreach { topic =>
          mqttPublisher ! MQTTPublisher.PublishString(if (status.isOn) "ON" else "OFF", topic)
        }
        Behaviors.same

      case KumoDeviceCommand(commandTopic, command) =>
        for {
          stateTopic <- commandTopicsToStateTopics.get(commandTopic)
          actor <- stateTopicsToDevices.get(stateTopic)
          _ <- {
            actor ! (command.trim.toLowerCase match {
              case "on" => KumoDevice.ModeHeat // TODO seasonal logic to switch between heat & cool?
              case "off" => KumoDevice.TurnOff
            })
            None
          }
        } yield {}
        Behaviors.same
    }
  }
}