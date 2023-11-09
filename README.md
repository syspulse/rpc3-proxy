# rpc3-proxy

Caching Proxy for EVM RPC.

- Proxy understand `batch` requests and can cache individual requests inside the batch

- Proxy supports `latest` to cache it as `block number`

- Cache is controlled:
   - ttl : time to live for one request
   - gc: Garbage collection for expired requests

- Multiple RPC nodes are supported for load-balancing/failover

The size of cache is not limited, use `gc` option


## Run 

### Run with cache 12 seconds

```
./run-rpc3.sh -d=http://geth:8545 --cache.ttl=120000
```

### Run with 4 Threads pool 

```
./run-rpc3.sh -d=http://geth:8545 --proxy.threads=4
```


### Testing fail-over scenario

This scenario simulates the connection failure of the first node and fail-over to the second. Proxy must stay on the healthy node as long as possible. 

All network and protocol failures are retry-enabled with:
`--rpc.retry=3 --rpc.delay=1000`

Run one HTTP pong:

```
./http-server.sh RSP_Batch_1.json
```

Run proxy with One node failure:

```
./run-rpc3.sh -d=http://localhost:8000,http://localhost:8300
```

Execute request:
```
./rpc3-post.sh REQ_Batch_latest-Tx.json
```

### Testing retry

This scenario simulates first node 429 and fail-over to the second node

Run HTTP server with 429 Response

```
CODE=429 ./http-server.sh RSP_Batch_1.json
```
```
./run-rpc3.sh -d=http://localhost:8300,http://geth1:8545
```

Execute request:
```
./rpc3-post.sh REQ_Batch_latest-Tx.json
```
