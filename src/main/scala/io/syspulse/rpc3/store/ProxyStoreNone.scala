package io.syspulse.rpc3.store

import scala.util.Try
import scala.util.{Success,Failure}
import scala.collection.immutable

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.scalalogging.Logger

import io.jvm.uuid._
import scala.concurrent.Future

import spray.json._
import io.syspulse.rpc3.server.ProxyRpcReq
import io.syspulse.rpc3.server.ProxyJson
import akka.http.scaladsl.model.HttpHeader

class ProxyStoreNone extends ProxyStore {
  val log = Logger(s"${this}")

  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  import ProxyJson._
  
  def rpc(req:String,headers:Seq[HttpHeader]) = {
    log.info(s"req='${req}', headers=${headers}")

    val request = if(req.trim.startsWith("{")) {
      req.parseJson.convertTo[ProxyRpcReq]
    } else
      ProxyRpcReq(jsonrpc = "",method = "",params = List.empty,id = 100)

    Future {
      s"""{"jsonrpc": "2.0", "error": {"code": -32601, "message": "Not implemented"}, "id": ${request.id}}"""
    }
  }
}
