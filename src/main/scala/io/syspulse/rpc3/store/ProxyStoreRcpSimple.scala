package io.syspulse.rpc3.store

import scala.util.Try
import scala.util.{Success,Failure}
import scala.collection.immutable

import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors

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
import io.syspulse.rpc3.pool.RpcPool
import io.syspulse.rpc3.pool.RpcSession
import akka.http.scaladsl.model.HttpHeader

class ProxyStoreRcpSimple(pool:RpcPool)(implicit config:Config,cache:ProxyCache) 
  extends ProxyStoreRcp(pool)(config,cache) {
  
  def batch(uri:String,req:String,headers:Seq[HttpHeader],session:RpcSession):Future[String] = {
    val rr = decodeBatch(req)    
    val key = rr.map(r => getKey(r)).mkString("_")

    val rsp = cache.find(key) match {
      case None => http(uri,req,headers)          
      case Some(rsp) => Future(rsp)
    }

    // save to cache and other manipulations
    for {
      r0 <- rsp
      _ <- Future(cache.cache(key,r0))
    } yield r0
  }

}

