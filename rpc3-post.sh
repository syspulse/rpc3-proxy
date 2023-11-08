#!/bin/bash

SERVICE_URI=${SERVICE_URI:-http://localhost:8080/api/v1/rpc3}

DATA_JSON=${1:-REQ_latest.json}
BLOCK=${2}

if [ "$BLOCK" != "" ]; then
   >&2 echo $BLOCK
   TMP=/tmp/DATA_JSON.json
   cat $DATA_JSON | sed "s/latest/$2/g" >$TMP
   DATA_JSON=$TMP
fi

curl -S -s -D /dev/stderr -X POST --data @"$DATA_JSON" -H 'Content-Type: application/json' -H "Authorization: Bearer $ACCESS_TOKEN" $SERVICE_URI/
