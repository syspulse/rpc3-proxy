package io.syspulse.rpc3.pool

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

class RpcSessionLoadBalance(pool:Seq[String],rpcFailback:Long = 10000L, maxRetry:Int = 3,maxLaps:Int = 1) extends RpcSession(pool) {
  
  var i = 0  
  var r = maxRetry  
  var l = 0
  var i0 = i
  var failedNodes:Array[Long] = pool.map(_ => 0L).toArray
  var f = -1
  
  def next():String = this.synchronized {    
    
    //println(s"i=${i}: r=${r}, f=${f}, failed=${failedNodes.toSeq}")
    if(f != -1) {
      // return if retrying failing
      return pool(f)
    }
    
    val rpc = pool(i)
    i0 = i
    
    // find next non-failed
    val range = Range(i + 1,failedNodes.size) ++ Range(0,i + 1)
    val now = System.currentTimeMillis
    val i_next = range.filter(i => (now - failedNodes(i)) >= rpcFailback ).toList
        
    //println(s"next=${i_next}: failed=${failedNodes.toSeq}")
    
    i = i_next match {
      case Nil => 
        // no available, continue with current
        l = l + 1
        r = maxRetry
        
        return rpc
        
      case h :: _ =>         
        h  // get the next non failed one
    }

    // marked as non-failed, error will fail it
    failedNodes(i) = 0L

    r = maxRetry
    
    rpc
  }

  def failed():String = this.synchronized {

    f = i0 
    failedNodes(f) = System.currentTimeMillis

    val rpc = pool(f)
    
    r = r - 1    
    
    if( r == 0) {
      f = -1
    }

    rpc
  }
  
  def available:Boolean = this.synchronized {
    //println(s"=======> ${l},${maxLaps}")
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


class RpcPoolLoadBalance(pool:Seq[String])(implicit config:Config) extends RpcPool {  
  def pool():Seq[String] = pool
  def connect(req:String) = {
    session.connect()
    session
  }

  // persistant pool
  val session = new RpcSessionLoadBalance(pool,config.rpcFailback, config.rpcRetry,config.rpcLaps)
}
