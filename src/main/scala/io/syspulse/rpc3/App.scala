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
  
  cacheTTL:Long = 10000L,
  cacheGC:Long = 30000L,
  
  rpcThreads:Int = 4,  
  rpcTimeout:Long = 150L,
  rpcRetry:Int = 3,
  rpcLaps:Int = 1,
  rpcDelay:Long = 1000L,
  rpcFailback:Long = 10000L,
  
  
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
        ArgLong('_', "cache.gc",s"Cache GC interval, msec (def: ${d.cacheGC})"),
        ArgLong('_', "cache.ttl",s"Cache TTL, msec (def: ${d.cacheTTL})"),
        
        ArgLong('_', "rpc.timeout",s"RPC Timeout (connect), msec (def: ${d.rpcTimeout})"),
        
        ArgInt('_', "rpc.threads",s"Number of threads (def: ${d.rpcThreads})"),
        ArgInt('_', "rpc.retry",s"Number of retries (def: ${d.rpcThreads})"),
        ArgInt('_', "rpc.laps",s"Number of pool lapses (def: ${d.rpcLaps})"),
        ArgLong('_',"rpc.delay",s"Delay between retry, msec (def: ${d.rpcDelay})"),
        ArgLong('_',"rpc.failback",s"Delay between failback retry (to previously failed node), msec (def: ${d.rpcFailback})"),
        
        ArgCmd("server","Command"),
        ArgCmd("client","Command"),
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
      cacheGC = c.getLong("cache.gc").getOrElse(d.cacheGC),
      cacheTTL = c.getLong("cache.ttl").getOrElse(d.cacheTTL),

      rpcTimeout = c.getLong("rpc.timeout").getOrElse(d.rpcTimeout),
      
      rpcThreads = c.getInt("rpc.threads").getOrElse(d.rpcThreads),
      rpcRetry = c.getInt("rpc.retry").getOrElse(d.rpcRetry),
      rpcLaps = c.getInt("rpc.laps").getOrElse(d.rpcLaps),
      rpcDelay = c.getLong("rpc.delay").getOrElse(d.rpcDelay),
      rpcFailback = c.getLong("rpc.failback").getOrElse(d.rpcFailback),
      
      cmd = c.getCmd().getOrElse(d.cmd),
      rpc = c.getParams(),
    )

    Console.err.println(s"Config: ${config}")
    Console.err.println(s"RPC: ${config.rpc}")
    
    implicit val cache = try { config.cache.split("://").toList match {      
      case "expire" :: time :: _ => new ProxyCacheExpire(time.toLong)
      case "expire" :: Nil => new ProxyCacheExpire(config.cacheTTL,config.cacheGC)
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
