package io.syspulse.rpc3.store

import scala.util.Try
import scala.util.{Success,Failure}
import scala.collection.immutable

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.scalalogging.Logger

import akka.http.scaladsl.Http

import io.jvm.uuid._
import scala.concurrent.Future

import spray.json._
import io.syspulse.rpc3.server.ProxyRpcReq
import io.syspulse.rpc3.server.ProxyJson

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.StatusCodes

import io.syspulse.rpc3.Config
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.ContentTypes
import io.syspulse.rpc3.cache.ProxyCache

class ProxyStoreRcp(rpcUri:String="")(implicit config:Config,cache:ProxyCache) extends ProxyStore {
  val log = Logger(s"${this}")
  
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  implicit val as: ActorSystem = ActorSystem()

  import ProxyJson._

  val uri = if(rpcUri.isEmpty()) config.rpcUri else rpcUri
  
  def rpc(req:String) = {
    log.info(s"req='${req}'")

    val response = if(req.trim.startsWith("{")) {
      //req.parseJson.convertTo[ProxyRpcReq]
      
      val rsp = cache.find(req) match {
        case None => 
          Http()
            .singleRequest(HttpRequest(HttpMethods.POST,uri, entity = HttpEntity(ContentTypes.`application/json`,req)))
            .flatMap(res => { 
              res.status match {
                case StatusCodes.OK => 
                  val body = res.entity.dataBytes.runReduce(_ ++ _)
                  body.map(_.utf8String)
                case _ => 
                  val body = res.entity.dataBytes.runReduce(_ ++ _)
                  body.map(_.utf8String)
              }
            })

        case Some(rsp) => Future(rsp)
      }

      // save to cache and other manipulations
      for {
        r0 <- rsp
        _ <- Future(cache.cache(req,r0))
      } yield r0
            
      
    } else {
      Future {
        s"""{"jsonrpc": "2.0", "error": {"code": -32601, "message": "Emtpy message"}, "id": 0}"""
      }
    }

    response 
  }
}
