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
abstract class RpcSession(rpcs:Seq[Future[String]]) {
  def next():Future[String]
  def available:Boolean
  def delay:Long
  def retry:Int
}

class RpcSessionFailFast(rpcs:Seq[Future[String]],maxRetry:Int = 3,maxLaps:Int = 2) extends RpcSession(rpcs) {
  @volatile
  var i = 0
  @volatile
  var r = maxRetry
  @volatile
  var lap = 0
  def next():Future[String] = {
    
    if(i == rpcs.size) {
      i = 0
      r = maxRetry - 1
      lap = lap + 1        
    }

    val rpc = rpcs(i)      
    
    if(r == 0) {
      i = i + 1
      r = maxRetry
    }

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
  def connect(rpcs:Seq[Future[String]]):RpcSession
}

class RpcPoolSticky(pool:Seq[String]) extends RpcPool {
  def pool():Seq[String] = pool
  def connect(rpcs:Seq[Future[String]]) = {
    new RpcSessionFailFast(rpcs)
  }
}

  