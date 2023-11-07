# rpc3-proxy

Caching Proxy for EVM RPC.

Proxy understand `batch` requests and can cache individual requests inside the batch


## Run 

### Run with cache 12 seconds

```
./run-rpc3.sh -d=http://geth:8545 --cache.ttl=120000
```

### Run with 4 Threads pool 

```
./run-rpc3.sh -d=http://geth:8545 --proxy.threads=4
```
