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
  def delay:Long
  def retry:Int  
}

class RpcSessionFailFast(pool:Seq[String],maxRetry:Int = 3,maxLaps:Int = 2) extends RpcSession(pool) {
  
  var i = 0  
  var r = maxRetry  
  var lap = 0
  
  def get():String = this.synchronized( 
    pool(i)
  )

  def next():String = this.synchronized {
    
    if(r == 0) {
      i = i + 1
      r = maxRetry
    }

    if(i == pool.size) {
      i = 0
      r = maxRetry
      lap = lap + 1
    }

    val rpc = pool(i)

    r = r - 1

    rpc
  }
  
  def available:Boolean = this.synchronized {
    lap < maxLaps
  }
  def delay:Long = 1000L
  
  def retry = this.synchronized { r }
}

// --- Pool -------------------------------------------------------------------------------
trait RpcPool {
  def pool():Seq[String] 
  def connect(req:String):RpcSession
}

class RpcPoolSticky(pool:Seq[String]) extends RpcPool {  
  def pool():Seq[String] = pool
  def connect(req:String) = {
    sticky
  }

  // persistant pool
  val sticky = new RpcSessionFailFast(pool)
}
