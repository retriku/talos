package talos.http

import java.time.ZonedDateTime

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status}

import scala.concurrent.duration.FiniteDuration


object CircuitBreakerStatsActor {
  def props: Props = Props(new CircuitBreakerStatsActor)


  case class CircuitBreakerStats
    (
      name: String,
      requestCount: Long,
      currentTime: ZonedDateTime,
      isCircuitBreakerOpen: Boolean,
      errorPercentage: Float,
      errorCount: Long,
      rollingCountFailure: Long,
      rollingCountExceptionsThrown: Long,
      rollingCountTimeout: Long,
      rollingCountShortCircuited: Long,
      rollingCountSuccess: Long,
      rollingCountFallbackSuccess: Long,
      latencyExecute_mean: FiniteDuration,
      latencyExecute: Map[String, FiniteDuration],
      latencyTotal_mean: FiniteDuration,
      latencyTotal: Map[String, FiniteDuration],
      propertyValue_metricsRollingStatisticalWindowInMilliseconds: FiniteDuration
    )

}

class CircuitBreakerStatsActor extends Actor with ActorLogging {

  import talos.http.CircuitBreakerStatsActor._

  override def receive: Receive = sendEventsTo(Set.empty)

  private[this] def sendEventsTo(streamTo: Set[ActorRef]): Receive = {
    case CircuitBreakerEventsSource.Start(streamingActor) =>
      context.become(sendEventsTo(streamTo + streamingActor))
    case cbs: CircuitBreakerStats =>
      streamTo.foreach(_ ! cbs)
    case CircuitBreakerEventsSource.Done(actorRef) =>
      val newSet = streamTo - actorRef
      actorRef ! Status.Success
      context.become(sendEventsTo(newSet))
  }
}
