package io.syspulse.rpc3.cache

import scala.collection.concurrent
import scala.jdk.CollectionConverters._
import java.util.concurrent.ConcurrentHashMap

import scala.util.Try
import scala.concurrent.Future
import scala.collection.immutable
import io.jvm.uuid._

class ProxyCacheNone() extends ProxyCache {
  
  def find(req:String):Option[String] = None

  def cache(req:String,rsp:String):String = rsp
}

