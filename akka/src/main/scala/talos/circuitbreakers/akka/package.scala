package talos.circuitbreakers

import java.util.concurrent.Executors

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.pattern.{CircuitBreaker => AkkaCB}
import cats.effect.IO
import talos.events.TalosEvents.model._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, TimeoutException}

package object akka {


  class AkkaEventBus(implicit actorSystem: ActorSystem) extends EventBus[ActorRef]{

    override def subscribe[T](subscriber: ActorRef, topic: Class[T]): Option[ActorRef] =
      if (actorSystem.eventStream.subscribe(subscriber, topic))
        Some(subscriber)
      else
        None

    override def unsubsribe(a: ActorRef): Unit = actorSystem.eventStream.unsubscribe(a)

    override def publish[A <: AnyRef](a: A): Unit = actorSystem.eventStream.publish(a)
  }


  class AkkaCircuitBreaker[Subscriber] private (val name: String, cbInstance: AkkaCB)
         (implicit eventBus: EventBus[ActorRef]) extends TalosCircuitBreaker[AkkaCB, IO] {


    override def protect[A](task: IO[A]): IO[A] =
      IO.fromFuture {
        IO(protectUnsafe(task))
      }

    private[AkkaCircuitBreaker] implicit val timer = IO.timer(AkkaCircuitBreaker.fallbackTimeoutExecutionContext)
    private[AkkaCircuitBreaker] implicit val contextShift = IO.contextShift(AkkaCircuitBreaker.forkIOExecutionContext)

    override def protectWithFallback[A, E](task: IO[A], fallback: IO[E]): IO[Either[E, A]] = {
      protect(task).map[Either[E, A]](Right(_)).handleErrorWith {
        _ =>
          fallback.timeout(TalosCircuitBreaker.FAST_FALLBACK_DURATION).map { v =>
            eventBus.publish(FallbackSuccess(name))
            Left(v)
          }.handleErrorWith {
            case _: TimeoutException =>
              eventBus.publish(FallbackRejected(name))
              IO.raiseError(new FallbackTimeoutError(name))
            case t =>
              eventBus.publish(FallbackFailure(name))
              IO.raiseError(t)
          }
      }
    }


    private val circuitBreakerInstance = wrap(
      cbInstance,
      name
    )


    private def wrap(circuitBreaker: AkkaCB, identifier: String): AkkaCB = {
      def publish[A <: AnyRef](event: A): Unit = eventBus.publish(event)
      circuitBreaker.addOnCallSuccessListener(
        elapsedTime => publish(SuccessfulCall(identifier, elapsedTime nanoseconds))
      ).addOnCallFailureListener(
        elapsedTime => publish(CallFailure(identifier, elapsedTime nanoseconds))
      ).addOnCallTimeoutListener(
        elapsedTime => publish(CallTimeout(identifier, elapsedTime nanoseconds))
      ).addOnOpenListener(
        () => publish(CircuitOpen(identifier))
      ).addOnHalfOpenListener(
        () => publish(CircuitHalfOpen(identifier))
      ).addOnCloseListener(
        () => publish(CircuitClosed(identifier))
      ).addOnCallBreakerOpenListener(
        () => publish(ShortCircuitedCall(identifier))
      )
    }

    override val circuitBreaker: IO[AkkaCB] = IO.pure(circuitBreakerInstance)

    private def protectUnsafe[A](task: IO[A]): Future[A] =
      circuitBreakerInstance.callWithCircuitBreaker(() => task.unsafeToFuture())

  }

  object AkkaCircuitBreaker {
    def apply(
       name: String,
       maxFailures: Int,
       callTimeout: FiniteDuration,
       resetTimeout: FiniteDuration
     )(implicit actorSystem: ActorSystem): TalosCircuitBreaker[AkkaCB, IO] = {
      apply(name, AkkaCB(actorSystem.scheduler, maxFailures, callTimeout, resetTimeout))
    }

    def apply(name: String, circuitBreaker: AkkaCB)(implicit actorSystem: ActorSystem): TalosCircuitBreaker[AkkaCB, IO] = {
      implicit val eventBus: EventBus[ActorRef] = new AkkaEventBus()
      implicit val akkaCircuitBreaker: AkkaCircuitBreaker[ActorRef] =
        new AkkaCircuitBreaker[ActorRef](name, circuitBreaker)
      Talos.circuitBreaker
    }

    private[akka] val fallbackTimeoutExecutionContext = ExecutionContext.fromExecutor(Executors.newScheduledThreadPool(2))
    private[akka] val forkIOExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

  }

  final implicit class AkkaCircuitBreakerSyntax(val circuitBreaker: AkkaCB) extends AnyVal {
    def withEventReporting(name: String)(implicit actorSystem: ActorSystem): TalosCircuitBreaker[AkkaCB, IO] = {
      AkkaCircuitBreaker(name, circuitBreaker)
    }
  }

}
