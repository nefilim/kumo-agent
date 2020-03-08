package org.nefilim.kumo.agent

import akka.actor.typed._
import akka.actor.typed.scaladsl._
import com.typesafe.scalalogging.LazyLogging
import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._


object Server extends LazyLogging {

  case class KumoAgentConfig(devices: List[KumoDeviceConfig], mqtt: KumoMQTTConfig)
  case class KumoDeviceConfig(ip: String, apiConfig: KumoDeviceAPIConfig, mqtt: KumoDeviceMQTTConfig)
  case class KumoDeviceAPIConfig(statusKey: String, modeHeatKey: String, modeCoolKey: String, modeOffKey: String)
  case class KumoDeviceMQTTConfig(stateTopic: String, commandTopic: String)
  case class KumoMQTTConfig(broker: String)

  def main(args: Array[String]) {
    val kumoAgentConfig = ConfigFactory.load().as[KumoAgentConfig]("kumo-agent")

    logger.info(s"kumo agent config: ${kumoAgentConfig}")
    ActorSystem(Server(kumoAgentConfig), "ServerMain")
  }

  sealed trait Command
  def apply(kumoAgentConfig: KumoAgentConfig): Behavior[Command] = {
    Behaviors.setup { context =>
      val coordinator = context.spawn(DeviceCoordinator(kumoAgentConfig), "KumoCoordinator")

      context.watch(coordinator)

      Behaviors.receiveSignal {
        case (_, Terminated(_)) => Behaviors.stopped
      }
    }
  }
}