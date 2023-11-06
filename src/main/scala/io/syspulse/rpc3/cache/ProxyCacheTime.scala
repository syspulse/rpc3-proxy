package io.syspulse.rpc3.cache

import scala.collection.concurrent
import scala.jdk.CollectionConverters._
import java.util.concurrent.ConcurrentHashMap

import scala.util.Try
import scala.concurrent.Future
import scala.collection.immutable
import io.jvm.uuid._

case class CacheRsp(ts:Long,rsp:String)

class ProxyCacheTime(ttl:Long = 10000L) extends ProxyCache {

  protected val cache:concurrent.Map[String,CacheRsp] = new ConcurrentHashMap().asScala
  
  def find(req:String):Option[String] = {
    cache.get(req) match {
      case Some(c) =>
        val now = System.currentTimeMillis()
        if( now - c.ts < ttl ) {          
          Some(c.rsp)
        } else {
          // remove expired
          cache.remove(req)
          None
        }
      case None => None
    }
  }

  def cache(req:String,rsp:String):String = {
    cache.put(req,CacheRsp(System.currentTimeMillis(),rsp))
    rsp
  }
}

