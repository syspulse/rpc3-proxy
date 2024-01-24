#!/bin/bash

SERVICE_URI=${SERVICE_URI:-http://localhost:8080/api/v1/rpc3}

DATA_JSON=${1:-REQ_latest.json}
BLOCK=${2:-latest}
COUNT=${COUNT:-1}
SLEEP=${SLEEP:-0}
ID=${ID:-5}

# if [ "$BLOCK" != "" ]; then
#    >&2 echo $BLOCK
#    TMP=/tmp/DATA_JSON_1.json
#    cat $DATA_JSON | sed "s/latest/$BLOCK/g" | sed "s/\"id\":1/\"id\":${ID}/g" >$TMP
#    DATA_JSON=$TMP
# fi


for c in `seq 0 $COUNT`; do   
   if [ "$BLOCK" != "latest" ]; then
      if [ "$BLOCK" == "random" ]; then
         B=$((RANDOM))
      else 
         B=$BLOCK
      fi
      >&2 echo ">>> $B"
      TMP=/tmp/DATA_JSON_${B}.json
      cat $DATA_JSON | sed "s/latest/$B/g" | sed "s/\"id\":1/\"id\":${ID}/g" >$TMP
   else
      TMP=/tmp/DATA_JSON_${BLOCK}.json
      cp $DATA_JSON $TMP      
   fi 

   curl -S -s -D /dev/stderr -X POST --data @"$TMP" -H 'Content-Type: application/json' -H "Authorization: Bearer $ACCESS_TOKEN" $SERVICE_URI/
   sleep $SLEEP
   rm $TMP
done
