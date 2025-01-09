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
import io.syspulse.rpc3.pool._
import io.syspulse.rpc3.server.ProxyRoutes

import io.jvm.uuid._

import io.syspulse.skel.FutureAwaitable._
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

case class Config(
  host:String="0.0.0.0",
  port:Int=8080,
  uri:String = "/api/v1/rpc3",

  datastore:String = "rpc://",
  cache:String = "expire://",
  pool:String = "sticky://", //"sticky://http://localhost:8300,http://localhost:8301",

  //rpc:Seq[String]=Seq("http://localhost:8300"),
  
  cacheTTL:Long = 30000L,           // ttl for history (non-latest)
  cacheLatest:Long = 12000L,        // ttl for `latest`
  cacheGC:Long = 60000L,            //   
  
  rpcThreads:Int = 4,  
  rpcTimeout:Long = 150L,
  rpcRetry:Int = 3,
  rpcLaps:Int = 1,
  rpcDelay:Long = 1000L,
  rpcFailback:Long = 10000L,
  rpcThrottle:Long = 0L,
  rpcCompress:String = "",//"deflate,gzip",
  rpcHeaders:Seq[String] = Seq(),

  apiKey:String = "", // suffix to url (api key)
  
  cmd:String = "server",
  rpc: Seq[String] = Seq("http://localhost:8300"),
)

object App extends skel.Server {
  
  def main(args:Array[String]):Unit = {
    Console.err.println(s"args: '${args.mkString(",")}'")

    val d = Config()
    val c = Configuration.withPriority(Seq(
      new ConfigurationAkka,
      new ConfigurationProp,
      new ConfigurationEnv, 
      new ConfigurationArgs(args,"squid3","",
        ArgString('h', "http.host",s"listen host (def: ${d.host})"),
        ArgInt('p', "http.port",s"listern port (def: ${d.port})"),
        ArgString('u', "http.uri",s"api uri (def: ${d.uri})"),

        ArgString('d', "datastore",s"Datastore [none://,rpc://] (def: ${d.datastore})"),        
        ArgString('_', "pool",s"Cache [sticky://,lb://] (def: ${d.pool})"),
        //ArgString('_', "rpc",s"RPC hosts (def: ${d.rpc})"),
        
        ArgString('c', "cache.type",s"Cache [none,time://] (def: ${d.cache})"),
        ArgLong('_', "cache.ttl",s"Cache TTL for Historical data (non-latest), msec (def: ${d.cacheTTL})"),
        ArgLong('_', "cache.latest",s"Cache TTL for Latest data, msec (def: ${d.cacheLatest})"),
        ArgLong('_', "cache.gc",s"Cache GC interval, msec (def: ${d.cacheGC})"),
        
        ArgLong('_', "rpc.timeout",s"RPC Timeout (connect), msec (def: ${d.rpcTimeout})"),
        
        ArgInt('_', "rpc.threads",s"Number of threads (def: ${d.rpcThreads})"),
        ArgInt('_', "rpc.retry",s"Number of retries (def: ${d.rpcThreads})"),
        ArgInt('_', "rpc.laps",s"Number of pool lapses (def: ${d.rpcLaps})"),
        ArgLong('_',"rpc.delay",s"Delay between retry, msec (def: ${d.rpcDelay})"),
        ArgLong('_',"rpc.failback",s"Delay between failback retry (to previously failed node), msec (def: ${d.rpcFailback})"),
        ArgLong('_',"rpc.throttle",s"Delay between Requests, msec (def: ${d.rpcThrottle})"),
        ArgString('_', "rpc.compress",s"RPC compression. empty is no compress (def: ${d.rpcCompress})"),
        ArgString('_', "rpc.headers",s"RPC headers (def: ${d.rpcHeaders})"),

        ArgString('_', "api.key",s"Cache [none,time://] (def: ${d.cache})"),
        
        ArgCmd("server","Command"),
        // ArgCmd("client","Command"),
        ArgParam("<rpc,...>","List of RPC nodes (added to --pool)"),
        ArgLogging()
      ).withExit(1)
    )).withLogging()

    implicit val config = Config(
      host = c.getString("http.host").getOrElse(d.host),
      port = c.getInt("http.port").getOrElse(d.port),
      uri = c.getString("http.uri").getOrElse(d.uri),
      
      datastore = c.getString("datastore").getOrElse(d.datastore),      
      pool = c.getString("pool").getOrElse(d.pool),
      //rpc = c.getListString("rpc",d.rpc),

      cache = c.getString("cache.type").getOrElse(d.cache),            
      cacheTTL = c.getLong("cache.ttl").getOrElse(d.cacheTTL),
      cacheLatest = c.getLong("cache.latest").getOrElse(d.cacheLatest),
      cacheGC = c.getLong("cache.gc").getOrElse(d.cacheGC),

      rpcTimeout = c.getLong("rpc.timeout").getOrElse(d.rpcTimeout),
      
      rpcThreads = c.getInt("rpc.threads").getOrElse(d.rpcThreads),
      rpcRetry = c.getInt("rpc.retry").getOrElse(d.rpcRetry),
      rpcLaps = c.getInt("rpc.laps").getOrElse(d.rpcLaps),
      rpcDelay = c.getLong("rpc.delay").getOrElse(d.rpcDelay),
      rpcFailback = c.getLong("rpc.failback").getOrElse(d.rpcFailback),
      rpcThrottle = c.getLong("rpc.throttle").getOrElse(d.rpcThrottle),
      rpcCompress = c.getString("rpc.compress").getOrElse(d.rpcCompress),
      rpcHeaders = c.getListString("rpc.headers",d.rpcHeaders),

      apiKey = c.getString("api.key").getOrElse(d.apiKey),
      
      cmd = c.getCmd().getOrElse(d.cmd),
      rpc = c.getParams(),
    )

    Console.err.println(s"Config: ${config}")
    Console.err.println(s"RPC: ${config.rpc}")
    
    implicit val cache = try { config.cache.split("://").toList match { 
      case "expire" :: ttl :: ttlLatest :: freq :: Nil => new ProxyCacheExpire(ttl.toLong,ttlLatest.toLong,freq.toLong)
      case "expire" :: ttl :: ttlLatest :: _ => new ProxyCacheExpire(ttl.toLong,ttlLatest.toLong,config.cacheGC)
      case "expire" :: ttl :: _ => new ProxyCacheExpire(ttl.toLong)
      case "expire" :: Nil => new ProxyCacheExpire(config.cacheTTL,config.cacheLatest,config.cacheGC)
      case "none" :: Nil => new ProxyCacheNone()
      case _ => {        
        Console.err.println(s"Uknown cache: '${config.cache}'")
        sys.exit(1)
      }
    }} catch {
      case e:Exception =>
        log.error(s"Failed to create cache",e)
        sys.exit(1)
    }
    
    val pool = try { config.pool.split("://").toList match {
      case "http" ::  uri => new RpcPoolSticky(("http://"+uri.mkString("://")).split(",").toSeq)
      case "https" ::  uri => new RpcPoolSticky(("https://"+uri.mkString("://")).split(",").toSeq)

      case "sticky" ::  Nil => new RpcPoolSticky(config.rpc)
      case "lb" :: Nil => new RpcPoolLoadBalance(config.rpc)
      case "pool" :: Nil => new RpcPoolSticky(config.rpc)
      
      case "sticky" ::  uri => new RpcPoolSticky(uri.mkString("://").split(",").toSeq)
      case "lb" :: uri => new RpcPoolLoadBalance(uri.mkString("://").split(",").toSeq)
      case "pool" :: uri => new RpcPoolSticky(uri.mkString("://").split(",").toSeq)
      case _ => {        
        Console.err.println(s"Uknown pool: '${config.pool}'")
        sys.exit(1)
      }
    }} catch {
      case e:Exception =>
        log.error(s"Failed to create pool",e)
        sys.exit(1)
    }    

    val store = try { config.datastore.split("://").toList match {          
      //case "dir" :: dir ::  _ => new ProxyStoreDir(dir)
      case "simple" :: Nil => new ProxyStoreRcpSimple(pool)
      case "simple" :: uri => new ProxyStoreRcpSimple(pool)
      case "rpc" :: Nil => new ProxyStoreRcpBatch(pool)
      case "rpc" :: uri => new ProxyStoreRcpBatch(pool)
      
      case "none" :: _ => new ProxyStoreNone()
      case _ => 
        Console.err.println(s"Uknown datastore: '${config.datastore}'")
        sys.exit(1)      
    }} catch {
      case e:Exception =>
        log.error(s"Failed to create store",e)
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
