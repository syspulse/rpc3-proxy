#!/bin/bash

SERVICE_URI=${SERVICE_URI:-http://localhost:8080/api/v1/rpc3}

DATA_JSON=${1:-REQ_latest.json}

2> echo $DATA_JSON
curl -S -s -D /dev/stderr -X POST --data @"$DATA_JSON" -H 'Content-Type: application/json' -H "Authorization: Bearer $ACCESS_TOKEN" $SERVICE_URI/
