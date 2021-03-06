package talos.gateway

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.testkit.TestKit
import cats.effect.IO
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, urlEqualTo}
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

import pureconfig.generic.auto._
import config.pureconfigExt._

import cats.implicits._

import talos.gateway.config.GatewayConfig

class BulkheadingSpec extends TestKit(ActorSystem("BulkheadingSpec"))
  with FlatSpecLike
  with BeforeAndAfterAll
  with Matchers {

  private val configString =
    """
      |{
      |    services: [
      |      {
      |        secure: false,
      |        host: "localhost",
      |        port: 9002,
      |        mappings: [
      |          {
      |            gateway-path: "/animals/dogs",
      |            methods: [GET],
      |            target-path: "/dogs/"
      |          }
      |        ],
      |        max-inflight-requests: 1,
      |        call-timeout: 5 seconds,
      |        importance: High
      |      },
      |      {
      |        secure: false,
      |        host: "localhost",
      |        port: 9003,
      |        mappings: [
      |          {
      |            gateway-path: "/vehicles/bikes",
      |            methods: [GET],
      |            target-path: "/bikes/"
      |          }
      |        ]
      |        max-inflight-requests: 4,
      |        call-timeout: 2 seconds,
      |        importance: Low
      |      }
      |    ],
      |    port: 18080,
      |    interface: "0.0.0.0"
      |}
    """.stripMargin

  val gatewayServer = GatewayServer(pureconfig.loadConfigOrThrow[GatewayConfig]
    (ConfigFactory.parseString(configString)))

  val dogsWireMockServer = new WireMockServer(wireMockConfig().port(9002))
  val vehiclesWireMockServer = new WireMockServer(wireMockConfig().port(9003))

  def initialiseMockServer(port: Int, path: String, mockServer: WireMockServer, delay: FiniteDuration) = {
    mockServer.start()

    val wireMock = new WireMock("localhost", port)
    wireMock.register(
      get(urlEqualTo(path))
        .willReturn(
          aResponse().withFixedDelay(delay.toMillis.intValue())
            .withStatus(200))
    )
    WireMock.configureFor("localhost", port)
  }
  override def beforeAll(): Unit = {
    initialiseMockServer(9002, "/dogs/", dogsWireMockServer, 5 seconds)
    initialiseMockServer(9003, "/bikes/", vehiclesWireMockServer, 100 millis)
  }


  override def afterAll(): Unit = {
    import scala.concurrent.ExecutionContext.Implicits._
    dogsWireMockServer.stop()
    vehiclesWireMockServer.stop()
    val termination = IO(gatewayServer.map(_.terminate(5 seconds))) *> IO(system.terminate()) *>
      IO.unit
    termination.unsafeRunSync()
  }

  "services" must "be isolated" in {
    Http().singleRequest(HttpRequest(uri = Uri("http://localhost:18080/animals/dogs")))
    Http().singleRequest(HttpRequest(uri = Uri("http://localhost:18080/animals/dogs")))
    Http().singleRequest(HttpRequest(uri = Uri("http://localhost:18080/animals/dogs")))
    Http().singleRequest(HttpRequest(uri = Uri("http://localhost:18080/animals/dogs")))
    val awaitableResult = Http().singleRequest(HttpRequest(uri = Uri("http://localhost:18080/vehicles/bikes")))

    println(Await.result(awaitableResult, 2 seconds))
  }

  "overflowing one queue" must "not affect another" in {
    for (_ <- 1 to 32) {
      Http().singleRequest(HttpRequest(uri = Uri("http://localhost:18080/animals/dogs")))
    }
    val awaitableResult = Http().singleRequest(HttpRequest(uri = Uri("http://localhost:18080/vehicles/bikes")))

    println(Await.result(awaitableResult, 3 seconds))
  }


}
