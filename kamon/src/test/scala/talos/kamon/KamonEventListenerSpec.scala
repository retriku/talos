package talos.kamon

import akka.actor.ActorSystem
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.scaladsl.adapter._
import com.typesafe.config.Config
import kamon.metric.{MetricValue, PeriodSnapshot}
import kamon.{Kamon, MetricReporter}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import talos.events.TalosEvents.model._

import scala.concurrent.duration._
class KamonEventListenerSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  var gatheringValues = Map.empty[String, Long]

  val testKit = ActorTestKit()

  override def afterAll() = {
    testKit.shutdownTestKit()
  }

  Kamon.addReporter(new MetricReporter {
    override def reportPeriodSnapshot(snapshot: PeriodSnapshot): Unit = {
      def name(metricValue: MetricValue): String =
        s"${metricValue.name}-${metricValue.tags.get("eventType").get}"
      snapshot.metrics.counters.foreach {
        mv =>
          gatheringValues += (name(mv) -> gatheringValues.get(name(mv)).map(_ + mv.value).getOrElse(mv.value))
      }
    }

    override def start(): Unit = ()

    override def stop(): Unit = ()

    override def reconfigure(config: Config): Unit = ()
  })

  "circuit breaker" should "integrate with kamon" in {


    implicit val untypedActorSystem: ActorSystem = testKit.system.toUntyped

    val circuitBreakerName = "myCircuitBreaker"

    val cancellableStatsAggregation = StatsAggregator.kamon()


    for (i <- 1 to 10) yield untypedActorSystem.eventStream.publish(SuccessfulCall(circuitBreakerName, i seconds))

    for (_ <- 1 to 5) yield untypedActorSystem.eventStream.publish(CallFailure(circuitBreakerName, 5 seconds))
    untypedActorSystem.eventStream.publish(CircuitOpen(circuitBreakerName))

    for (_ <- 1 to 5) yield untypedActorSystem.eventStream.publish(ShortCircuitedCall(circuitBreakerName))

    untypedActorSystem.eventStream.publish(FallbackSuccess(circuitBreakerName))
    untypedActorSystem.eventStream.publish(FallbackFailure(circuitBreakerName))
    untypedActorSystem.eventStream.publish(FallbackRejected(circuitBreakerName))

    Thread.sleep(2000)

    gatheringValues shouldBe Map(
      "circuit-breaker-myCircuitBreaker-success-call" -> 10,
      "circuit-breaker-myCircuitBreaker-failed-call" -> 5,
      "circuit-breaker-myCircuitBreaker-short-circuited" -> 5,
      "circuit-breaker-myCircuitBreaker-circuit-open" -> 1,
      "circuit-breaker-myCircuitBreaker-fallback-success" -> 1,
      "circuit-breaker-myCircuitBreaker-fallback-failure" -> 1,
      "circuit-breaker-myCircuitBreaker-fallback-rejected" -> 1
    )

    cancellableStatsAggregation.unsafeRunSync()

  }

}
