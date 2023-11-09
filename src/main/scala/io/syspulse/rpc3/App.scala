package io.syspulse.rpc3

import scala.concurrent.duration.Duration
import scala.concurrent.Future
import scala.concurrent.Await

import io.syspulse.skel
import io.syspulse.skel.util.Util
import io.syspulse.skel.config._

import io.syspulse.rpc3._
import io.syspulse.rpc3.store._
import io.syspulse.rpc3.cache._
import io.syspulse.rpc3.server.ProxyRoutes

import io.jvm.uuid._

import io.syspulse.skel.FutureAwaitable._
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

case class Config(
  host:String="0.0.0.0",
  port:Int=8080,
  uri:String = "/api/v1/rpc3",

  datastore:String = "none://",
  cache:String = "expire://",
  
  cacheTTL:Long = 10 * 9000L,
  cacheGC:Long = 10 * 60 * 1000L,
  
  rpcThreads:Int = 4,
  rpcPool:String = "http://localhost:8300,http://localhost:8301",
  rpcTimeout:Long = 150L,
  rpcRetry:Int = 3,
  rpcLaps:Int = 1,
  rpcDelay:Long = 1000L,
  
  cmd:String = "server",
  params: Seq[String] = Seq(),
)

object App extends skel.Server {
  
  def main(args:Array[String]):Unit = {
    Console.err.println(s"args: '${args.mkString(",")}'")

    val d = Config()
    val c = Configuration.withPriority(Seq(
      new ConfigurationAkka,
      new ConfigurationProp,
      new ConfigurationEnv, 
      new ConfigurationArgs(args,"skel-Proxy","",
        ArgString('h', "http.host",s"listen host (def: ${d.host})"),
        ArgInt('p', "http.port",s"listern port (def: ${d.port})"),
        ArgString('u', "http.uri",s"api uri (def: ${d.uri})"),

        ArgString('d', "datastore",s"Datastore [none://,rpc://] (def: ${d.datastore})"),
        ArgString('c', "cache",s"Cache [none,time://] (def: ${d.cache})"),
        
        ArgLong('_', "cache.gc",s"Cache GC interval, msec (def: ${d.cacheGC})"),
        ArgLong('_', "cache.ttl",s"Cache TTL, msec (def: ${d.cacheTTL})"),
        
        ArgLong('_', "rpc.timeout",s"RPC Timeout (connect), msec (def: ${d.rpcTimeout})"),
        ArgString('_', "rpc.pool",s"RPC pool (def: ${d.rpcPool})"),
        ArgInt('_', "rpc.threads",s"Number of threads (def: ${d.rpcThreads})"),
        ArgInt('_', "rpc.retry",s"Number of retries (def: ${d.rpcThreads})"),
        ArgInt('_', "rpc.laps",s"Number of pool lapses (def: ${d.rpcLaps})"),
        ArgLong('_',"rpc.delay",s"Delay between retry, msec (def: ${d.rpcDelay})"),
        
        ArgCmd("server","Command"),
        ArgCmd("client","Command"),
        ArgParam("<params>",""),
        ArgLogging()
      ).withExit(1)
    )).withLogging()

    implicit val config = Config(
      host = c.getString("http.host").getOrElse(d.host),
      port = c.getInt("http.port").getOrElse(d.port),
      uri = c.getString("http.uri").getOrElse(d.uri),
      
      datastore = c.getString("datastore").getOrElse(d.datastore),
      cache = c.getString("cache").getOrElse(d.cache),
            
      cacheGC = c.getLong("cache.gc").getOrElse(d.cacheGC),
      cacheTTL = c.getLong("cache.ttl").getOrElse(d.cacheTTL),

      rpcTimeout = c.getLong("rpc.timeout").getOrElse(d.rpcTimeout),
      rpcPool = c.getString("rpc.pool").getOrElse(d.rpcPool),
      rpcThreads = c.getInt("rpc.threads").getOrElse(d.rpcThreads),
      rpcRetry = c.getInt("rpc.retry").getOrElse(d.rpcRetry),
      rpcLaps = c.getInt("rpc.laps").getOrElse(d.rpcLaps),
      rpcDelay = c.getLong("rpc.delay").getOrElse(d.rpcDelay),
      
      cmd = c.getCmd().getOrElse(d.cmd),
      params = c.getParams(),
    )

    Console.err.println(s"Config: ${config}")
    
    implicit val cache = config.cache.split("://").toList match {      
      case "expire" :: time :: _ => new ProxyCacheExpire(time.toLong)
      case "expire" :: Nil => new ProxyCacheExpire(config.cacheTTL,config.cacheGC)
      case "none" :: Nil => new ProxyCacheNone()
      case _ => {        
        Console.err.println(s"Uknown cache: '${config.cache}'")
        sys.exit(1)
      }
    }

    val store = config.datastore.split("://").toList match {          
      //case "dir" :: dir ::  _ => new ProxyStoreDir(dir)
      case "simple" :: Nil => new ProxyStoreRcpSimple()
      case "simple" :: uri => new ProxyStoreRcpSimple("http://" + uri.mkString("://"))
      case "rpc" :: Nil => new ProxyStoreRcpBatch()
      case "rpc" :: uri => new ProxyStoreRcpBatch("http://" + uri.mkString("://"))
      case "http" :: _ => new ProxyStoreRcpBatch(config.datastore)
      case "https" :: _ => new ProxyStoreRcpBatch(config.datastore)

      case "pool" :: Nil => new ProxyStoreRcpBatch(config.rpcPool)
      case "pool" :: pool => new ProxyStoreRcpBatch(pool.mkString("://"))

      case "none" :: _ => new ProxyStoreNone()
      case _ => 
        Console.err.println(s"Uknown datastore: '${config.datastore}'")
        sys.exit(1)      
    }    

    config.cmd match {
      case "server" => 
        
        
        run( config.host, config.port,config.uri,c,
          Seq(
            (ProxyRegistry(store),"ProxyRegistry",(r, ac) => new ProxyRoutes(r)(ac,config) )
          )
        ) 
    }
  }
}