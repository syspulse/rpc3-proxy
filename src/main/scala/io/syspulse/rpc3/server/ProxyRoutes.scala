package io.syspulse.rpc3.server

import com.typesafe.scalalogging.Logger
import io.jvm.uuid._
import scala.util.{Try,Success,Failure}
import java.nio.file.Paths

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.FileIO

import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.server.RejectionHandler
import akka.http.scaladsl.model.StatusCodes._

import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.{Operation, Parameter}
import io.swagger.v3.oas.annotations.parameters.RequestBody
// import javax.ws.rs.{Consumes, POST, GET, DELETE, Path, Produces}
// import javax.ws.rs.core.MediaType
import jakarta.ws.rs.{Consumes, POST, PUT, GET, DELETE, Path, Produces}
import jakarta.ws.rs.core.MediaType


import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter

import io.syspulse.skel.service.Routeable
import io.syspulse.skel.service.CommonRoutes

import io.syspulse.skel.Command

// import io.syspulse.skel.auth.permissions.Permissions
// import io.syspulse.skel.auth.RouteAuthorizers

import io.syspulse.rpc3._
import io.syspulse.rpc3.store.ProxyRegistry
import io.syspulse.rpc3.store.ProxyRegistry._
import io.syspulse.rpc3.server._
import io.syspulse.skel.service.telemetry.TelemetryRegistry
import akka.http.scaladsl.server.AuthorizationFailedRejection

@Path("/")
class ProxyRoutes(registry: ActorRef[Command])(implicit context: ActorContext[_],config:Config) extends CommonRoutes with Routeable {
  //with RouteAuthorizers {
  
  implicit val system: ActorSystem[_] = context.system
  
  // implicit val permissions = Permissions()

  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  import ProxyJson._
  
  val metricGetCount: Counter = Counter.build().name("rpc3_get_total").help("Rpc3 gets").register(TelemetryRegistry.registry)
  val metricPostCount: Counter = Counter.build().name("rpc3_post_total").help("Rpc3 posts").register(TelemetryRegistry.registry)
  
  def rpcProxy(req:String,headers:Seq[HttpHeader]): Future[Try[String]] = registry.ask(ProxyRpc(req,headers,_))
      

  @POST @Path("/") @Consumes(Array(MediaType.APPLICATION_JSON))
  def rpcRoute = post {
    extractRequest { request =>
      entity(as[String]) { req =>        
        onSuccess(rpcProxy(req,request.headers)) { rsp =>
          metricPostCount.inc()
          complete(StatusCodes.OK, rsp)
        }
      }
    }
  }
  
  val corsAllow = CorsSettings(system.classicSystem)
    //.withAllowGenericHttpRequests(true)
    .withAllowCredentials(true)
    .withAllowedMethods(Seq(HttpMethods.OPTIONS,HttpMethods.GET,HttpMethods.POST,HttpMethods.PUT,HttpMethods.DELETE,HttpMethods.HEAD))

  override def routes: Route = cors(corsAllow) {
      concat(
        pathEndOrSingleSlash {
          if(config.apiKey.isBlank())
            concat(            
              rpcRoute
            )
          else
            reject(AuthorizationFailedRejection)
        },
        pathPrefix(Segment) { apiKey => 
          pathEndOrSingleSlash {
            if(config.apiKey == apiKey)
              concat(
                rpcRoute
              )
            else
              reject(AuthorizationFailedRejection)
            
          }
        }
      )
  }
}
