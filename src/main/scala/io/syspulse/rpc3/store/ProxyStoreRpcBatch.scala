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

class ProxyStoreRcpBatch(rpcUri:String="")(implicit config:Config,cache:ProxyCache) 
  extends ProxyStoreRcp(rpcUri)(config,cache) {
  
  import ProxyJson._

  // parse reponse as Array of strings
  def parseBatchRawRes(rsp:String):Try[Vector[JsValue]] = { 
    try {
      val rjson = rsp.parseJson
      // get as Array of json values
      val rr = rjson.asInstanceOf[JsArray].elements
      Success(rr)
    } catch {
      case e:Exception => Failure(e)        
    }      
  }


  def batch(req:String):Future[String] = {
    val rr = decodeBatch(req)
        
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

    // check that everything was cached
    if(rspUnCached.size == 0) {
      val response = "[" + rspAll.map(_._2.get).mkString(",") + "]"
      return Future{response}
    }


    // prepare requests only for uncached
    val reqUnCached = rspUnCached.map(r => r._1.toJson.compactPrint)
    
    // prepare request for uncached
    val reqRpc = "[" + reqUnCached.mkString(",") + "]"

    // log.info(s"${reqRpc.take(80)} --> ${uri}")
        
    for {
      rsp <- proxy(reqRpc)
      fresh <- {
        // This step converts from JsonArray[JsonValue] -> Array[String]
        // It is required to have an index into New response assembly and cache

        // parse response to JSON tree
        val rspParsed = parseBatchRawRes(rsp)
        val rawJs = rspParsed match {
          case Success(r) => 
            // chehc that response contains the same number in batch
            if(r.size != reqUnCached.size) {
              //log.error(s"response size=${r.size}, expected=${reqUnCached.size}")
              throw new Exception(s"response size=${r.size}, expected=${reqUnCached.size}")
            }
            r
          case f @ Failure(e) => 
            //log.error(s"failed to parse Rpc response: ${e}")
            //Vector[JsValue]()
            throw new Exception(s"failed to parse Rpc response: ${e}")
        }
        // convert to Array[String]
        val fresh = rawJs.map(_.compactPrint)
        Future(fresh)
      }
      all <- {
        // Assembly response with cached + fresh
        var i = -1
        val all = rspAll.map( r => {
          if(r._2.isDefined) r._2.get
          else {
            i = i + 1
            fresh(i)
          }
        })
        Future(all)
      }
      _ <- {
        // Insert fresh -> Cache
        var i = -1
        rspUnCached.foreach( r => {
          val key = getKey(r._1)
          i = i + 1
          cache.cache(key,fresh(i))
        })
        Future(all)
      }
      rspClient <- {
        val response = "[" + all.mkString(",") + "]"
        log.debug(s"response: ${response}")
        Future {response}
      }
    } yield rspClient
  }


}

