package org.nefilim.kumo.agent

import java.util.UUID

import akka.actor.typed.{Behavior, PostStop}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import com.hivemq.client.mqtt.mqtt5.{Mqtt5AsyncClient, Mqtt5Client}
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.{Mqtt5ConnAck, Mqtt5ConnAckReasonCode}
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PublishResult
import com.typesafe.scalalogging.LazyLogging

import scala.util.{Failure, Success}
import scala.compat.java8.FutureConverters._

object MQTTPublisher extends LazyLogging {
  sealed trait Command

  final case class PublishString(command: String, topic: String) extends Command
  final case class PublishResult(command: PublishString, publishResult: Either[Throwable, Mqtt5PublishResult]) extends Command
  final case class ConnectionAck(ack: Mqtt5ConnAck) extends Command
  final case class ConnectionFailure(e: Throwable) extends Command

  def apply(mqttBrokerIP: String): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withStash(10) { stash =>
        new MQTTPublisher(mqttBrokerIP, context, stash).start()
      }
    }
  }
}

class MQTTPublisher private (
    mqttBrokerIP: String,
    context: ActorContext[MQTTPublisher.Command],
    stashBuffer: StashBuffer[MQTTPublisher.Command]) {

  import MQTTPublisher._

  context.setLoggerName(this.getClass.getName)

  // TODO should we move this to the constructor? what happens when restarting?
  private def start(): Behavior[Command] = {
    val client = Mqtt5Client.builder()
      .identifier(UUID.randomUUID().toString())
      .serverHost(mqttBrokerIP)
      .buildAsync()

    val connection = client.connect().toScala
    context.pipeToSelf(connection) {
      case Success(ack) => ConnectionAck(ack)
      case Failure(e) => ConnectionFailure(e)
    }
    connecting(client)
  }

  private def connecting(client: Mqtt5AsyncClient): Behavior[Command] = {
    Behaviors.receiveMessage {
      case ConnectionFailure(e) =>
        context.log.error(s"failed to connect to MQTT broker at ${mqttBrokerIP}", e)
        Behaviors.same // TODO need to retry?
      case ConnectionAck(ack) if (ack.getReasonCode != Mqtt5ConnAckReasonCode.SUCCESS) =>
        context.log.error(s"failed to connect to MQTT server at ${mqttBrokerIP}, ${ack.getReasonCode} ${ack.getReasonString}")
        Behaviors.same // TODO need to retry?
      case ConnectionAck(ack) =>
        context.log.info(s"connected to MQTT server at ${mqttBrokerIP}, ${ack.getReasonCode}")
        stashBuffer.unstashAll(connected(client))
    }
  }

  private def connected(client: Mqtt5AsyncClient): Behavior[Command] = {
    Behaviors
      .receiveMessage[MQTTPublisher.Command] {
        case c@PublishString(command, topic) =>
          context.log.info(s"publishing command [$command] to connected [${client.getState.isConnected}] mqtt [${}] on topic [$topic]")
          context.pipeToSelf(client.publishWith().topic(topic).payload(command.getBytes).send().toScala) {
            case Success(r) => PublishResult(c, Right(r))
            case Failure(e) => PublishResult(c, Left(e))
          }
          Behaviors.same
        case PublishResult(command, result) if result.isLeft =>
          context.log.info(s"publishing command [$command] to connected [${client.getState.isConnected}] mqtt FAILED", result.left)
          Behaviors.same
        case PublishResult(command, result) if result.isRight =>
          result.map { r =>
            if (r.getError.isPresent)
              context.log.info(s"publishing command [$command] to connected [${client.getState.isConnected}] mqtt FAILED", r.getError.get())
            else
              context.log.info(s"publishing command [$command] to connected [${client.getState.isConnected}] mqtt SUCCEEDED: ${r.getPublish}")
          }
          Behaviors.same
      }
      .receiveSignal {
        case (_, signal) if signal == PostStop =>
          client.disconnect()
          Behaviors.same
      }
  }
}