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

class ProxyStoreRcp(rpcUri:String="")(implicit config:Config,cache:ProxyCache) extends ProxyStore {
  val log = Logger(s"${this}")
  
  //implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  implicit val ec: scala.concurrent.ExecutionContext = 
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))

  implicit val as: ActorSystem = ActorSystem()

  import ProxyJson._

  val uri = if(rpcUri.isEmpty()) config.rpcUri else rpcUri
  
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

  // parse reponse as Array of strings
  def parseBatchRawRes(req:String):Try[Array[String]] = { 
    try {
      Success(req.parseJson.convertTo[Array[String]])
    } catch {
      case e:Exception => Failure(e)        
    }      
  }

  def single(req:String) = {
    parseSingleReq(req) match {
      case Success(r) =>         
        //(r.method,r.params,r.id)
        r
      case Failure(e) => 
        log.warn(s"failed to parse: ${e}")
        ProxyRpcReq("2.0","",List(),0)
    }    
  }

  def batch(req:String) = {
    parseBatchReq(req) match {
      case Success(r) =>         
        r
      case Failure(e) => 
        log.warn(s"failed to parse: ${e}")
        Array[ProxyRpcReq]()
    } 
  }

  def getKey(r:ProxyRpcReq) = {
    s"${r.method}-${r.params.toString}"
  }

  def proxy(req:String) = {
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
  }

  def batchOptimized(req:String) = {
    val rr = batch(req)
        
    // prepare array for response with Cached and UnCached
    val rspAll = rr.map( r => {
      val key = getKey(r)
      cache.find(key) match {
        case Some(rsp) => r -> Some(rsp)
        case None => r -> None
      }
    })

    // get only UnCached
    val rspUnCached = rspAll.filter(r => ! r._2.isDefined)
    // prepare requests only for uncached
    val reqUnCached = rspUnCached.map(r => r._1.toJson.compactPrint)
    
    // prepare only request
    val reqRpc = "[" + reqUnCached.mkString(",") + "]"

    // call request
    val rspRpc = proxy(reqRpc)            
    
    // save to cache and other manipulations
    for {
      rsp <- rspRpc
      raw <- {
        val rspParsed = parseBatchRawRes(rsp)
        val raw = rspParsed match {
          case Success(r) => 
            r
          case f @ Failure(e) => 
            log.error(s"failed to parse Rpc response: ${e}")
            Array[String]()
        }
        Future(raw)
      }
      all <- {
        var i = -1
        val all = rspAll.map( r => {
          if(r._2.isDefined) r._2.get
          else {
            i = i + 1
            raw(i)          
          }
        })
        Future(all)
      }
      _ <- {
        var i = -1
        rspUnCached.foreach( r => {
          val key = getKey(r._1)
          i = i + 1
          cache.cache(key,raw(i))
        })
        Future(all)
      }
      rspClient <- {
        Future {
          "[" + all.mkString(",") + "]"
        }
      }
    } yield rspClient
  }

  def batchCached(req:String) = {
    val rr = batch(req)    
    val key = rr.map(r => getKey(r)).mkString("_")

    val rsp = cache.find(key) match {
      case None => proxy(req)          
      case Some(rsp) => Future(rsp)
    }

    // save to cache and other manipulations
    for {
      r0 <- rsp
      _ <- Future(cache.cache(key,r0))
    } yield r0
  }

  def singleCached(req:String) = {
    val r = single(req)
    val key = getKey(r)

    val rsp = cache.find(key) match {
      case None => proxy(req)          
      case Some(rsp) => Future(rsp)
    }

    // save to cache and other manipulations
    for {
      r0 <- rsp
      _ <- Future(cache.cache(key,r0))
    } yield r0
  }

  def rpc(req:String) = {
    log.info(s"req='${req}'")

    val response = req.trim match {

      // single request
      case req if(req.startsWith("{")) => 
        singleCached(req)        
      
      // batch
      case req if(req.startsWith("[")) => 
        //batchOptimized(req)
        batchCached(req)
        
      case _ => 
        Future {
          s"""{"jsonrpc": "2.0", "error": {"code": -32601, "message": "Emtpy message"}, "id": 0}"""
        }
    }

    response 
  }
}
