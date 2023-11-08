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


abstract class ProxyStoreRcp(uriPool:String="")(implicit config:Config,cache:ProxyCache) extends ProxyStore {
  val log = Logger(s"${this}")
  
  import ProxyJson._
  //implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  implicit val ec: scala.concurrent.ExecutionContext = 
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(config.rpcThreads))

  implicit val as: ActorSystem = ActorSystem("proxy")

  implicit val sched = as.scheduler

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

  val uri = {
    val uu = if(uriPool.isEmpty()) config.rpcPool else uriPool
    uu.split(",")
  }.toSeq

  log.info(s"Pool: ${uri}")

  val rpcPool = new RpcPoolSticky(uri)
  
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
    val uri = session.get()
    
    val f = rpc1(uri,req)

    f recoverWith { 
      // case e if session.retry > 0 => 
      //   // retry to the same RPC
      //   log.warn(s"retry(${session.retry},${session.lap}): ${uri}")
      //   session.next()
      //   akka.pattern.after(FiniteDuration(config.rpcDelay,TimeUnit.MILLISECONDS), sched)(retry(req,session))         
      case e if session.available =>
        // switch to another RPC or fail
        log.warn(s"retry(${session.retry},${session.lap}): ${uri}")
        session.next()
        akka.pattern.after(FiniteDuration(config.rpcDelay,TimeUnit.MILLISECONDS), sched)(retry(req,session))      
    }
  }
    
  // --------------------------------------------------------------------------------- Proxy ---
  def rpc1(uri:String,req:String) = {
    log.info(s"${req.take(80)} --> ${uri}")

    lazy val http = Http()
    .singleRequest(HttpRequest(HttpMethods.POST,uri, entity = HttpEntity(ContentTypes.`application/json`,req)),
                   settings = retry_deterministic(as,FiniteDuration(100L,TimeUnit.MILLISECONDS)))
    .flatMap(res => { 
      res.status match {
        case StatusCodes.OK => 
          val body = res.entity.dataBytes.runReduce(_ ++ _)
          body.map(_.utf8String)
        case _ =>
          log.error(s"RPC error: ${res.status}")
          val body = res.entity.dataBytes.runReduce(_ ++ _)
          body.map(_.utf8String)
      }
    })
    http
  }

  def proxy(req:String) = {
                    
    val session = rpcPool.connect(req)    

    retry(req,session)
  }
 
  def single(req:String) = {
    val r = decodeSingle(req)
    val key = getKey(r)

    val rsp = cache.find(key) match {
      case None => 
        
        proxy(req)          
      case Some(rsp) => Future(rsp)
    }

    // save to cache and other manipulations
    for {
      r0 <- rsp
      _ <- Future(cache.cache(key,r0))
    } yield r0
  }

  def rpc(req:String) = {
    log.debug(s"req='${req}'")

    val response = req.trim match {

      // single request
      case req if(req.startsWith("{")) => 
        single(req)        
      
      // batch
      case req if(req.startsWith("[")) => 
        //batchOptimized(req)
        batch(req)
        
      case _ => 
        Future {
          s"""{"jsonrpc": "2.0", "error": {"code": -32601, "message": "Emtpy message"}, "id": 0}"""
        }
    }

    response 
  }

  def batch(req:String):Future[String]
}
