package io.syspulse.rpc3.server

import scala.collection.immutable

import io.jvm.uuid._

// {"jsonrpc": "2.0", "method": "subtract", "params": [42, 23], "id": 1}
// {"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest", false],"id":1}
final case class ProxyRpcReq(jsonrpc:String,method:String,params:List[Any],id:Any)

// {"jsonrpc": "2.0", "result": 19, "id": 1}
final case class ProxyRpcRes(jsonrpc:String,result:Any,id:Any)

// {"jsonrpc": "2.0", "error": {"code": -32601, "message": "Method not found"}, "id": "5"}

