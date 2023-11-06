package io.syspulse.rpc3.store

import scala.util.Try
import scala.concurrent.Future
import scala.collection.immutable
import io.jvm.uuid._

trait ProxyStore {
  
  def rpc(req:String):Future[String]
}

