package io.syspulse.rpc3.server

import scala.collection.immutable

import io.jvm.uuid._

// {"jsonrpc": "2.0", "method": "subtract", "params": [42, 23], "id": 1}
// {"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest", false],"id":1}
final case class ProxyRpcReq(jsonrpc:String,method:String,params:List[Any],id:Any)

// {"jsonrpc": "2.0", "result": 19, "id": 1}
// {"jsonrpc": "2.0", "result": ["hello", 5], "id": "9"}
final case class ProxyRpcRes(jsonrpc:String,result:Any,id:Any)

// {"jsonrpc": "2.0", "error": {"code": -32601, "message": "Method not found"}, "id": "5"}

// eth_blockNumber: {"jsonrpc":"2.0","id":1,"result":"0x1231992"}
final case class ProxyRpcBlockNumberRes(jsonrpc:String,result:String,id:Any)

// eth_getBlockByNumber: {"jsonrpc":"2.0","id":1,"result":{"hash":"0xa851cc422","number":"0x0",tx=[{"i":0},{"i":1}]},"id": 100000}
// interested only in 'number'
final case class ProxyRpcBlockResultRes(number:String)
final case class ProxyRpcBlockRes(jsonrpc:String,result:ProxyRpcBlockResultRes,id:Any)


