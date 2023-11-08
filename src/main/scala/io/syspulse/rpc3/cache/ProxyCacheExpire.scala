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
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

import io.prometheus.client.Counter

import io.syspulse.skel.cron.CronFreq
import io.syspulse.skel.service.telemetry.TelemetryRegistry

import spray.json._
import io.syspulse.rpc3.server.{ProxyRpcBlockRes,ProxyRpcBlockResultRes}
import io.syspulse.rpc3.server.ProxyJson

case class CacheRsp(ts:Long,rsp:String)

class ProxyCacheExpire(ttl:Long = 10000L,gcFreq:Long = 10000L) extends ProxyCache {
  val log = Logger(s"${this}")

  val metricCacheSizeCount: Counter = Counter.build().name("rpc3_cache_size").help("Cache size").register(TelemetryRegistry.registry)
  val metricCacheHitCount: Counter = Counter.build().name("rpc3_cache_hit").help("Cache hits").register(TelemetryRegistry.registry)
  val metricCacheMissCount: Counter = Counter.build().name("rpc3_cache_miss").help("Cache misses").register(TelemetryRegistry.registry)

  protected val cache:concurrent.Map[String,CacheRsp] = new ConcurrentHashMap().asScala

  val cron = new CronFreq(() => {
      //log.info(s"GC: ${cache.size}")
      var n = 0
      val now = System.currentTimeMillis()
      cache.foreach{ case(k,v) => {
        if(now - v.ts >= ttl) {
          cache.remove(k)
          n = n + 1
        }
      }}

      log.info(s"GC: ${cache.size}: removed=${n}")
      true
    },
    FiniteDuration(gcFreq,TimeUnit.MILLISECONDS),
    delay = gcFreq
  )
        
  cron.start()
  
  def find(key:String):Option[String] = {
    log.debug(s"find: ${key}")
        
    cache.get(key) match {
      case Some(c) =>
        val now = System.currentTimeMillis()
        if( now - c.ts < ttl ) {
          metricCacheHitCount.inc
          Some(c.rsp)
        } else {
          metricCacheMissCount.inc
          // remove expired
          cache.remove(key)
          None
        }
      case None => 
        metricCacheMissCount.inc
        None
    }
  }

  def cache(key:String,rsp:String):String = {
    import ProxyJson._

    val now = System.currentTimeMillis()
    cache.put(key,CacheRsp(now,rsp))

    // save special case of "latest" block
    val latest = key.startsWith(ProxyCache.getKey("eth_getBlockByNumber",Seq("latest")).stripSuffix(")"))
    if(latest) {
      try {
        val block = rsp.parseJson.convertTo[ProxyRpcBlockRes].result.number
                
        val keyBlock = key.replaceAll("latest",block)
        
        log.debug(s"latest: ${block} => Cache[${keyBlock}]")

        cache.put(keyBlock,CacheRsp(now,rsp))

      } catch {
        case e:Exception =>
          log.warn(s"could not parse latest: ",e)
      }
    }

    metricCacheSizeCount.inc()
    rsp
  }
}

