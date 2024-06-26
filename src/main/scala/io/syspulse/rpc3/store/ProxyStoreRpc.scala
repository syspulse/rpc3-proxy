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
import akka.actor.Scheduler
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.http.scaladsl.settings.ClientConnectionSettings
import scala.concurrent.Await

import io.syspulse.rpc3.pool.RpcPool
import io.syspulse.rpc3.pool.RpcSession

object Throttler {
  val BGL = new Object()
}

class Throttler(throttle:Long, bgl:Boolean=true) {
  def block():Unit = {
    if(throttle == 0L) return

    // throttler is global
    if(bgl) {
      Throttler.BGL.synchronized {
        Thread.sleep(throttle)
      }
    } else {
      this.synchronized {
        Thread.sleep(throttle)
      }
    }
  }
}

abstract class ProxyStoreRcp(pool:RpcPool)(implicit config:Config,cache:ProxyCache) extends ProxyStore {
  val log = Logger(s"${this}")
  
  import ProxyJson._
  //implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  implicit val ec: scala.concurrent.ExecutionContext = 
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(config.rpcThreads))

  implicit val as: ActorSystem = ActorSystem("proxy")

  implicit val sched = as.scheduler
  val throttler = new Throttler(config.rpcThrottle)

  def retry_1_deterministic(as:ActorSystem,timeout:FiniteDuration) = ConnectionPoolSettings(as)
                          .withBaseConnectionBackoff(FiniteDuration(1000,TimeUnit.MILLISECONDS))
                          .withMaxConnectionBackoff(FiniteDuration(1000,TimeUnit.MILLISECONDS))
                          .withMaxConnections(1)
                          .withMaxRetries(1)
                          .withConnectionSettings(ClientConnectionSettings(as)
                          .withIdleTimeout(timeout)
                          .withConnectingTimeout(timeout))

  def retry_deterministic(as:ActorSystem,timeout:FiniteDuration) = ConnectionPoolSettings(as)
                          .withBaseConnectionBackoff(timeout)
                          .withMaxConnectionBackoff(timeout)

  
  log.info(s"Pool: ${pool}")
  
  def parseSingleReq(req:String):Try[ProxyRpcReq] = { 
    try {
      Success(req.parseJson.convertTo[ProxyRpcReq])
    } catch {
      case e:Exception => Failure(e)        
    }      
  }

  def parseBatchReq(req:String):Try[Array[ProxyRpcReq]] = { 
    try {
      Success(req.parseJson.convertTo[Array[ProxyRpcReq]])
    } catch {
      case e:Exception => Failure(e)        
    }      
  }

  def decodeSingle(req:String) = {
    parseSingleReq(req) match {
      case Success(r) =>         
        //(r.method,r.params,r.id)
        r
      case Failure(e) => 
        log.warn(s"failed to parse: ${e}")
        ProxyRpcReq("2.0","",List(),0)
    }    
  }

  def decodeBatch(req:String) = {
    parseBatchReq(req) match {
      case Success(r) =>         
        r
      case Failure(e) => 
        log.warn(s"failed to parse: ${e}")
        Array[ProxyRpcReq]()
    } 
  }

  def getKey(r:ProxyRpcReq) = {
    ProxyCache.getKey(r.method,r.params)
  }

  def retry[T](f: => Future[T], delay: Long, max: Int)(implicit ec: ExecutionContext, sched: Scheduler): Future[T] = {
    f recoverWith { 
      case e if max > 0 => 
        log.error("retry: ",e)
        akka.pattern.after(FiniteDuration(delay,TimeUnit.MILLISECONDS), sched)(retry(f, delay, max - 1)) 
    }
  }

  def retry(req:String,session:RpcSession)(implicit ec: ExecutionContext, sched: Scheduler): Future[String] = {
    val uri = session.next()
    
    val f = rpc1(uri,req,session)

    f
    .recoverWith { 
      // case e if session.retry > 0 => 
      //   // retry to the same RPC
      //   log.warn(s"retry(${session.retry},${session.lap}): ${uri}")
      //   session.failed()
      //   akka.pattern.after(FiniteDuration(config.rpcDelay,TimeUnit.MILLISECONDS), sched)(retry(req,session))         
      case e if session.available =>
        // switch to another RPC or fail
        log.warn(s"retry(${session.retry},${session.lap}): ${uri}")
        session.failed()
        akka.pattern.after(FiniteDuration(config.rpcDelay,TimeUnit.MILLISECONDS), sched)(retry(req,session))  

      // case e =>
      //   log.warn(s"??? retry(${session.retry},${session.lap}): ${uri}: ${e.getMessage()}")
      //   akka.pattern.after(FiniteDuration(config.rpcDelay,TimeUnit.MILLISECONDS), sched)(retry(req,session))  
    }    
  }
    
  // --------------------------------------------------------------------------------- Proxy ---
  def rpc1(uri:String,req:String,session:RpcSession) = {
    log.info(s"${req.take(85)} --> ${uri}")

    // throttle here if neccessary
    throttler.block()

    val response = req.trim match {

      // single request
      case req if(req.startsWith("{")) => 
        single(uri,req)        
      
      // batch
      case req if(req.startsWith("[")) => 
        //batchOptimized(req)
        batch(uri,req,session)
        
      case _ => 
        Future {
          s"""{"jsonrpc": "2.0", "error": {"code": -32601, "message": "Emtpy message"}, "id": 0}"""
        }
    }
    response 
    

    // lazy val http = Http()
    // .singleRequest(HttpRequest(HttpMethods.POST, uri, entity = HttpEntity(ContentTypes.`application/json`,req)),
    //                settings = retry_deterministic(as,FiniteDuration(config.rpcTimeout,TimeUnit.MILLISECONDS)))
    // .flatMap(res => { 
    //   res.status match {
    //     case StatusCodes.OK => 
    //       val body = res.entity.dataBytes.runReduce(_ ++ _)
    //       body.map(_.utf8String)
    //     case _ =>
    //       log.error(s"RPC error: ${res.status}")
    //       val body = res.entity.dataBytes.runReduce(_ ++ _)
    //       val txt = Await.result(body.map(_.utf8String),FiniteDuration(5000L,TimeUnit.MILLISECONDS))
    //       throw new Exception(s"${res.status}: ${txt}")
    //   }
    // })
    // http
  }

  def http(uri:String,req:String) = {                    
    // val session = session0.getOrElse(pool.connect(req))
    // retry(req,session)

    lazy val http = Http()
    .singleRequest(HttpRequest(HttpMethods.POST, uri, entity = HttpEntity(ContentTypes.`application/json`,req)),
                   settings = retry_deterministic(as,FiniteDuration(config.rpcTimeout,TimeUnit.MILLISECONDS)))
    .flatMap(res => { 
      res.status match {
        case StatusCodes.OK => 
          val body = res.entity.dataBytes.runReduce(_ ++ _)
          body.map(_.utf8String)
        case _ =>
          log.error(s"RPC error: ${res.status}")
          val body = res.entity.dataBytes.runReduce(_ ++ _)
          val txt = Await.result(body.map(_.utf8String),FiniteDuration(5000L,TimeUnit.MILLISECONDS))
          throw new Exception(s"${res.status}: ${txt}")
      }
    })
    http
  }
 
  def single(uri:String,req:String) = {
    val r = decodeSingle(req)
    val key = getKey(r)

    val rsp = cache.find(key) match {
      case None =>         
        // save to cache only on missed 
        for {
          r0 <- http(uri,req)
          _ <- {            
            if(isError(Some(r0))) {
              log.warn(s"uncache: ${r0}")
              Future(r0)
            } else
              Future(cache.cache(key,r0))
          }
        } yield r0

      case Some(rsp) => 
        Future(rsp)
    }

    rsp 
  }

  def rpc(req:String) = {
    log.debug(s"req='${req}'")

    val session = pool.connect(req)

    retry(req,session)
    
    // val response = req.trim match {

    //   // single request
    //   case req if(req.startsWith("{")) => 
    //     single(req)        
      
    //   // batch
    //   case req if(req.startsWith("[")) => 
    //     //batchOptimized(req)
    //     batch(req)
        
    //   case _ => 
    //     Future {
    //       s"""{"jsonrpc": "2.0", "error": {"code": -32601, "message": "Emtpy message"}, "id": 0}"""
    //     }
    // }
    // response 
  }

  def batch(uri:String,req:String,session:RpcSession):Future[String]

  def isError(res:Option[String]):Boolean = {
    if(! res.isDefined) 
      return true
    else {
      // fast and dirty
      // this is checked agains every request.
      (res.get.contains("""error""") && res.get.contains("""code""")) ||
      (res.get.contains(""""result":null""") || res.get.contains(""""result": null"""))
    }
  }
}
