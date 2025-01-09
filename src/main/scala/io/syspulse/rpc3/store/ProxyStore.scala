package io.syspulse.rpc3.store

import scala.util.Try
import scala.concurrent.Future
import scala.collection.immutable
import io.jvm.uuid._
import akka.http.scaladsl.model.HttpHeader

trait ProxyStore {
  
  def rpc(req:String,headers:Seq[HttpHeader]):Future[String]
}
