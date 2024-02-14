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
import io.syspulse.rpc3.server.ProxyRpcBlockNumberRes

case class CacheRsp(ts:Long,rsp:String,latest:Boolean = false)

class ProxyCacheExpire(ttl:Long = 30000L,ttlLatest:Long = 12000L,gcFreq:Long = 10000L) extends ProxyCache {
  val log = Logger(s"${this}")
  
  val metricCacheSizeCount: Counter = Counter.build().name("rpc3_cache_size").help("Cache size").register(TelemetryRegistry.registry)
  val metricCacheHitCount: Counter = Counter.build().name("rpc3_cache_hit").help("Cache hits").register(TelemetryRegistry.registry)
  val metricCacheMissCount: Counter = Counter.build().name("rpc3_cache_miss").help("Cache misses").register(TelemetryRegistry.registry)

  protected val cache:concurrent.Map[String,CacheRsp] = new ConcurrentHashMap().asScala

  val cron = new CronFreq(() => {
      //log.info(s"GC: ${cache.size}")
      var nHot = 0
      var nCold = 0

      val now = System.currentTimeMillis()
      
      cache.foreach{ case(k,v) => {
        if(now - v.ts >= {if(v.latest) ttlLatest else ttl}) {
          cache.remove(k)
          if(v.latest) nHot = nHot + 1 else nCold = nCold + 1
        }
      }}

      val sz = cache.size      
      log.info(s"GC: size=${sz}: removed=(${nHot},${nCold}), hit=${metricCacheHitCount.get()}, miss=${metricCacheMissCount.get}")
      true
    },
    FiniteDuration(gcFreq,TimeUnit.MILLISECONDS),
    delay = gcFreq
  )
        
  cron.start()
  
  def find(key:String):Option[String] = {
            
    val rsp = cache.get(key) match {
      case Some(c) =>
        val now = System.currentTimeMillis()
        val expire = if(c.latest) ttlLatest else ttl
        
        if( now - c.ts < expire ) {
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

    log.debug(s"find: ${key} -> ${rsp}")
    rsp
  }

  def cache(key:String,rsp:String):String = {
    import ProxyJson._

    val now = System.currentTimeMillis()
    log.debug(s"cache: ${key}")
    
    // save special case of "latest" block
    val latest1 = key.startsWith(ProxyCache.getKey("eth_getBlockByNumber",Seq("latest")).stripSuffix(")"))       
    val latest2 = key.startsWith(ProxyCache.getKey("eth_blockNumber",Seq()).stripSuffix(")"))

    // save to cache as latest
    cache.put(key,CacheRsp(now,rsp,(latest1 || latest2)))    

    try {
      val block:String = 
        if(latest1) {
          rsp.parseJson.convertTo[ProxyRpcBlockRes].result.number
        }
        else ""
      
      if(block != "") {
        // replace 'latest' word with block number and save to Cold cache
        val keyBlock = key.replaceAll("latest",block)        
        log.debug(s"latest: ${block} => Cache[${keyBlock}]")

        // save to cache with a block number as non-latest
        cache.put(keyBlock,CacheRsp(now,rsp,false))
      }

    } catch {
      case e:Exception =>
        log.warn(s"could not parse latest: ",e)
    }

    metricCacheSizeCount.inc()
    rsp
  }
}

