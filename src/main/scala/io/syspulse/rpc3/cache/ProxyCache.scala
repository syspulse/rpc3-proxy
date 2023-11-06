package io.syspulse.rpc3.cache

import scala.util.Try
import scala.concurrent.Future
import scala.collection.immutable
import io.jvm.uuid._

trait ProxyCache {

  def find(req:String):Option[String]
  def cache(req:String,res:String):String
}

