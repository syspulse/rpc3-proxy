package io.syspulse.rpc3.cache

import scala.collection.concurrent
import scala.jdk.CollectionConverters._
import java.util.concurrent.ConcurrentHashMap

import scala.util.Try
import scala.concurrent.Future
import scala.collection.immutable
import io.jvm.uuid._
import scala.util.Success
import scala.util.Failure
import com.typesafe.scalalogging.Logger

case class CacheRsp(ts:Long,rsp:String)

class ProxyCacheTime(ttl:Long = 10000L) extends ProxyCache {
  val log = Logger(s"${this}")

  protected val cache:concurrent.Map[String,CacheRsp] = new ConcurrentHashMap().asScala
  
  def find(key:String):Option[String] = {
    log.info(s"find: ${key}")
        
    cache.get(key) match {
      case Some(c) =>
        val now = System.currentTimeMillis()
        if( now - c.ts < ttl ) {          
          Some(c.rsp)
        } else {
          // remove expired
          cache.remove(key)
          None
        }
      case None => None
    }
  }

  def cache(key:String,rsp:String):String = {
    cache.put(key,CacheRsp(System.currentTimeMillis(),rsp))
    rsp
  }
}

