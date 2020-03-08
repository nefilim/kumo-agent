package org.nefilim.kumo.agent

import java.util.UUID

import akka.actor.typed.{ActorRef, Behavior, PostStop}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.{Mqtt5AsyncClient, Mqtt5Client}
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.{Mqtt5ConnAck, Mqtt5ConnAckReasonCode}
import com.hivemq.client.mqtt.mqtt5.message.publish.{Mqtt5Publish, Mqtt5PublishResult}
import com.typesafe.scalalogging.LazyLogging
import org.nefilim.kumo.agent.DeviceCoordinator.KumoDeviceCommand

import scala.util.{Failure, Success}
import scala.compat.java8.FutureConverters._

object MQTTPublisher extends LazyLogging {
  sealed trait Command

  final case class PublishString(command: String, topic: String) extends Command
  final case class PublishResult(command: PublishString, publishResult: Either[Throwable, Mqtt5PublishResult]) extends Command
  final case class ConnectionAck(ack: Mqtt5ConnAck) extends Command
  final case class ConnectionFailure(e: Throwable) extends Command

  def apply(
      coordinator: ActorRef[DeviceCoordinator.Command],
      topics: Set[String],
      mqttBrokerIP: String
  ): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withStash(10) { stash =>
        new MQTTPublisher(coordinator, topics, mqttBrokerIP, context, stash).start()
      }
    }
  }
}

class MQTTPublisher private (
    coordinator: ActorRef[DeviceCoordinator.Command],
    topics: Set[String],
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

  private def createSubscriptions(client: Mqtt5AsyncClient): Unit = {
    topics.map { t =>
      context.log.info(s"Subscribing to MQTT topic [${t}]")
      client
        .subscribeWith()
        .topicFilter(t)
        .qos(MqttQos.AT_LEAST_ONCE)
        .callback(consumeMQTTMessage)
        .send().toScala
    }.foreach { f =>
      import context.executionContext
      f.onComplete {
        case Success(ack) =>
          context.log.info(s"successfully subscribed to topic, completed codes [${ack.getReasonCodes}] strings [${ack.getReasonString}]")
        case Failure(e) =>
          context.log.error(s"failed to subscribe to topic", e)
      }
    }
  }
  
  private def consumeMQTTMessage(m: Mqtt5Publish): Unit = {
    val command = new String(m.getPayloadAsBytes)
    context.log.info(s"consuming message [$command] from topic [${m.getTopic}]")
    coordinator ! DeviceCoordinator.KumoDeviceCommand(m.getTopic.toString, command)
  }

  private def connecting(client: Mqtt5AsyncClient): Behavior[Command] = {
    // TODO need stashing here for publishing commands
    Behaviors.receiveMessage {
      case ConnectionFailure(e) =>
        context.log.error(s"failed to connect to MQTT broker at ${mqttBrokerIP}", e)
        Behaviors.same // TODO need to retry?
      case ConnectionAck(ack) if (ack.getReasonCode != Mqtt5ConnAckReasonCode.SUCCESS) =>
        context.log.error(s"failed to connect to MQTT server at ${mqttBrokerIP}, ${ack.getReasonCode} ${ack.getReasonString}")
        Behaviors.same // TODO need to retry?
      case ConnectionAck(ack) =>
        context.log.info(s"connected to MQTT server at ${mqttBrokerIP}, ${ack.getReasonCode}")
        createSubscriptions(client)
        stashBuffer.unstashAll(connected(client))
      // commands
      case p: PublishString =>
        stashBuffer.stash(p)
        Behaviors.same
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