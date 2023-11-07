package io.syspulse.rpc3.server

import org.scalatest.{Ignore}
import org.scalatest.wordspec.{ AnyWordSpec}
import org.scalatest.matchers.should.{ Matchers}
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.{Try,Success,Failure}
import java.time._
// import io.syspulse.skel.util.Util

import spray.json._

class ProxyJsonSpec extends AnyWordSpec with Matchers {
  import ProxyJson._

  "ProxyJson" should {

    "decode single rpc response" in {
      val r1 = """{"jsonrpc":"2.0","result":{"hash":"0xa851cc422","difficulty":"0x0", "tx": [ {"i":0},{"i":1} ] },  "id": 100000}"""
      val j1 = r1.parseJson      
      info(s"${j1}")
      
      val ff = j1.asJsObject.fields
      info(s"${ff}")
      
      val id = ff("id")
      id.toString === ("100000")
      id.asInstanceOf[JsNumber] === (JsNumber(100000))

      val result = ff("result")
      info(s"result=${result.compactPrint}")

    }

    "decode batch rpc response" in {
      val r1 = """[ 
        {"jsonrpc":"2.0","id":1,"result":{"hash":"0x001","difficulty":"0x0", "tx": [ {"i":0},{"i":1} ] }} ,
        {"jsonrpc":"2.0","id":2,"result":{"hash":"0x002","difficulty":"0x0", "tx": [ {"i":0},{"i":1} ] }}
      ]"""
      val j1 = r1.parseJson      
      info(s"${j1}")
      
      val aa = j1.asInstanceOf[JsArray]
      info(s"${aa.elements(0)}")
      info(s"${aa.elements(1)}")            
    }

    "insert into batch rpc response" in {
      val r0 = """[ 
        {"jsonrpc":"2.0","id":1,"result":{"hash":"0x001","difficulty":100, "tx": [ {"i":0},{"i":1} ] }} ,
        {"jsonrpc":"2.0","id":3,"result":{"hash":"0x003","difficulty":300, "tx": [ {"i":0},{"i":1} ] }}
      ]"""
      val j0 = r0.parseJson      
    
      val r1 = """{"jsonrpc":"2.0","id":2,"result":{"hash":"0x002","difficulty":2000, "tx": [ {"i":0},{"i":1} ] }}"""

      val aa0 = j0.asInstanceOf[JsArray]
      val r2 = s"""[
         ${aa0.elements(0).compactPrint} ,
         ${r1} ,
         ${aa0.elements(1).compactPrint}
      ]"""
      
      info(s"r2=${r2}")
      val j2 = r2.parseJson
    }
  }    
}
