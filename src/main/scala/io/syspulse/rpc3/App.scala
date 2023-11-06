package io.syspulse.rpc3

import scala.concurrent.duration.Duration
import scala.concurrent.Future
import scala.concurrent.Await

import io.syspulse.skel
import io.syspulse.skel.util.Util
import io.syspulse.skel.config._

import io.syspulse.rpc3._
import io.syspulse.rpc3.store._
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

  timeout:Long = 3000L,

  rpcUri:String = "http://localhost:8300", //"http://geth.demo.hacken.cloud:8545",
  
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

        ArgString('d', "datastore",s"datastore [none,rpc] (def: ${d.datastore})"),
        ArgString('_', "timeout",s"Timeouts, msec (def: ${d.timeout})"),

        ArgString('_', "rpc.uri",s"RPC uri (def: ${d.rpcUri})"),
        
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
      timeout = c.getLong("timeout").getOrElse(d.timeout),

      rpcUri = c.getString("rpc.uri").getOrElse(d.rpcUri),
      
      cmd = c.getCmd().getOrElse(d.cmd),
      params = c.getParams(),
    )

    Console.err.println(s"Config: ${config}")
    
    config.cmd match {
      case "server" => 
        val store = config.datastore.split("://").toList match {          
          //case "dir" :: dir ::  _ => new ProxyStoreDir(dir)
          case "rpc" :: Nil => new ProxyStoreRcp()
          case "rpc" :: uri => new ProxyStoreRcp(uri.mkString("://"))
          case "http" :: _ => new ProxyStoreRcp(config.datastore)
          case "https" :: _ => new ProxyStoreRcp(config.datastore)
          
          case "none" :: Nil => new ProxyStoreNone()
          case _ => {
            Console.err.println(s"Uknown datastore: '${config.datastore}'")
            sys.exit(1)
          }
        }
        
        run( config.host, config.port,config.uri,c,
          Seq(
            (ProxyRegistry(store),"ProxyRegistry",(r, ac) => new ProxyRoutes(r)(ac,config) )
          )
        ) 
    }
  }
}