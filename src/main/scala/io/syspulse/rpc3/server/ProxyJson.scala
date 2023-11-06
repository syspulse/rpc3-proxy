package io.syspulse.rpc3.server

import io.syspulse.skel.service.JsonCommon

import spray.json.DefaultJsonProtocol

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives
import spray.json.{DefaultJsonProtocol, DeserializationException, JsString, JsValue, JsonFormat, deserializationError}

import io.syspulse.rpc3._
import io.syspulse.rpc3.server.{ProxyRpcReq,ProxyRpcRes}

object ProxyJson extends JsonCommon {
  
  import DefaultJsonProtocol._

  implicit val jf_rpc_req = jsonFormat4(ProxyRpcReq)
  implicit val jf_rpc_res = jsonFormat3(ProxyRpcRes)
  
}
