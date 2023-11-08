package io.syspulse.rpc3.store

import scala.util.Try
import scala.util.{Success,Failure}
import scala.collection.immutable

import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors

import com.typesafe.scalalogging.Logger

import io.jvm.uuid._
import scala.concurrent.Future

import io.syspulse.rpc3.Config
import io.syspulse.rpc3.cache.ProxyCache

// --- Session -------------------------------------------------------------------------------
abstract class RpcSession(pool:Seq[String]) {
  val id = util.Random.nextLong()
  def next():String
  def get():String
  def available:Boolean  
  def retry:Int
  def lap:Int
}

class RpcSessionFailFast(pool:Seq[String],maxRetry:Int = 3,maxLaps:Int = 1) extends RpcSession(pool) {
  
  var i = 0  
  var r = maxRetry  
  var l = 0
  var i0 = i
  
  def get():String = this.synchronized( 
    pool(i)
  )

  def next():String = this.synchronized {
    
    if(r == 0) {
      i = i + 1
      r = maxRetry

      if(i == i0) {
        // overlap
        l = l + 1
      }
    }

    if(i == pool.size) {
      i = 0
      r = maxRetry
      
      if(i == i0) {
        // overlap
        l = l + 1
      }
    }    

    val rpc = pool(i)

    r = r - 1

    rpc
  }
  
  def available:Boolean = this.synchronized {
    l < maxLaps
  }  
  
  def retry = this.synchronized { r }

  def lap = this.synchronized{ l }

  def connect() = this.synchronized{
    r = maxRetry
    i0 = i
    l = 0
  }
}

// --- Pool -------------------------------------------------------------------------------
trait RpcPool {
  def pool():Seq[String] 
  def connect(req:String):RpcSession
}

class RpcPoolSticky(pool:Seq[String])(implicit config:Config) extends RpcPool {  
  def pool():Seq[String] = pool
  def connect(req:String) = {
    sticky.connect()
    sticky
  }

  // persistant pool
  val sticky = new RpcSessionFailFast(pool,config.rpcRetry,config.rpcLaps)
}
