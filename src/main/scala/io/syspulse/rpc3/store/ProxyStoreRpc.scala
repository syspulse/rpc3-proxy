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


abstract class ProxyStoreRcp(rpcUri:String="")(implicit config:Config,cache:ProxyCache) extends ProxyStore {
  val log = Logger(s"${this}")
  
  import ProxyJson._
  //implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  implicit val ec: scala.concurrent.ExecutionContext = 
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(config.rpcThreads))

  implicit val as: ActorSystem = ActorSystem("proxy")

  implicit val sched = as.scheduler

  val uri = if(rpcUri.isEmpty()) config.rpcUri else rpcUri

  val rpcPool = new RpcPoolSticky(Seq(uri))
  
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
    //s"${r.method}-${r.params.toString}"
    ProxyCache.getKey(r.method,r.params)
  }

  def retry[T](f: => Future[T], delay: Long, max: Int)(implicit ec: ExecutionContext, sched: Scheduler): Future[T] = {
    f recoverWith { 
      case e if max > 0 => 
        log.error("retry: ",e)
        akka.pattern.after(FiniteDuration(delay,TimeUnit.MILLISECONDS), sched)(retry(f, delay, max - 1)) 
    }
  }

  def retry(session:RpcSession)(implicit ec: ExecutionContext, sched: Scheduler): Future[String] = {
    val f = session.next()    
    f recoverWith { 
      case e if session.retry > 0 =>
        log.warn(s"Try[${session.retry}]: ${f}")
        akka.pattern.after(FiniteDuration(session.delay,TimeUnit.MILLISECONDS), sched)(retry(session)) 
        //retry(session)
      case e if session.available =>
        log.warn(s"Try[${session.retry}]: next RPC: ${f}")
        akka.pattern.after(FiniteDuration(session.delay,TimeUnit.MILLISECONDS), sched)(retry(session)) 
      
    }
  }
    
  // --------------------------------------------------------------------------------- Proxy ---
  def proxy(req:String) = {
    // request to a single RPC
    def rpc1(uri:String,req:String) = {
      log.info(s"${req.take(80)} --> ${uri}")

      lazy val http = Http()
      .singleRequest(HttpRequest(HttpMethods.POST,uri, entity = HttpEntity(ContentTypes.`application/json`,req)))
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
    
    // pool
    // val rpc_pool = rpcPool.pool().map(uri => {
    //   // retry single rpc
    //   val rpc1_retry = retry(rpc1(uri,req),1000L,3)
    //   rpc1_retry
    // })

    val rpc_pool = rpcPool.pool().map(uri => rpc1(uri,req))

    val sess = rpcPool.connect(rpc_pool)

    retry(sess)
    
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
