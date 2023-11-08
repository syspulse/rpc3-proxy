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
abstract class RpcSession(req:String,pool:Seq[String]) {
  def next():String
  def available:Boolean
  def delay:Long
  def retry:Int
  def getReq():String = req
}

class RpcSessionFailFast(req:String,pool:Seq[String],maxRetry:Int = 3,maxLaps:Int = 2) extends RpcSession(req,pool) {
  @volatile
  var i = 0
  @volatile
  var r = maxRetry
  @volatile
  var lap = 0

  def next():String = {
        
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
  def available:Boolean = {
    lap < maxLaps
  }
  def delay:Long = 1000L
  def retry = r
}

// --- Pool -------------------------------------------------------------------------------
trait RpcPool {
  def pool():Seq[String] 
  def connect(req:String):RpcSession
}

class RpcPoolSticky(pool:Seq[String]) extends RpcPool {
  def pool():Seq[String] = pool
  def connect(req:String) = {
    new RpcSessionFailFast(req,pool)
  }
}

  