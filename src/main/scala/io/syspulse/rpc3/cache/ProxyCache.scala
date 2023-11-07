package io.syspulse.rpc3.cache

import scala.util.{Try,Failure,Success}

import scala.concurrent.Future
import scala.collection.immutable
import io.jvm.uuid._

trait ProxyCache {
  
  def find(key:String):Option[String]
  def cache(key:String,res:String):String
}

object ProxyCache {
  def getKey(method:String,params:Seq[Any]) = {
    s"${method}-${params.toString}"
  }
}