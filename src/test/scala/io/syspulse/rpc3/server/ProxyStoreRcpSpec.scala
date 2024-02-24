package io.syspulse.rpc3.server

import org.scalatest.{Ignore}
import org.scalatest.wordspec.{ AnyWordSpec}
import org.scalatest.matchers.should.{ Matchers}
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.{Try,Success,Failure}
import java.time._
// import io.syspulse.skel.util.Util

import spray.json._
import io.syspulse.rpc3.store.ProxyStoreRcpBatch
import io.syspulse.rpc3.Config
import io.syspulse.rpc3.pool.RpcPoolSticky
import io.syspulse.rpc3.cache.ProxyCacheNone

class ProxyStoreRpcSpec extends AnyWordSpec with Matchers {
  import ProxyJson._

  "ProxyStoreRpc" should {

    implicit val config = Config()
    val pool = new RpcPoolSticky(Seq("http://localhost:8301"))
    val cache = new ProxyCacheNone()
    val proxy = new ProxyStoreRcpBatch(pool)(config,cache)

    "decode error for code: 32000" in {
      val r1 = """{"jsonrpc": "2.0", "error": {"code": 32601, "message": "Emtpy message"}, "id": 0}"""
      val e1 = proxy.isError(Some(r1))      
      e1 should === (true)      

      val r2 = """{"error": {"code": 32601, "message": "Emtpy message"}, "id": 0},"jsonrpc": "2.0"}"""
      val e2 = proxy.isError(Some(r2))      
      e2 should === (true)      
    }

    "decode error for code: -32000" in {
      val r1 = """{"jsonrpc": "2.0", "error": {"code": -32601, "message": "Emtpy message"}, "id": 0}"""
      val e1 = proxy.isError(Some(r1))
      
      e1 should === (true)      
    }

    "decode error for result=null" in {
      val r1 = """{"jsonrpc":"2.0","id":"0x22bace8","result":null}"""
      val e1 = proxy.isError(Some(r1))      
      e1 should === (true)

      val r2 = """{"jsonrpc":"2.0","id":"0x22bace8","result": null}"""
      val e2 = proxy.isError(Some(r2))
      e2 should === (true)

      //id.asInstanceOf[JsNumber] should === (JsNumber(100000))      
    }

    
  }    
}
