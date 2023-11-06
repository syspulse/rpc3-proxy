#!/bin/bash

RSP_FILE=${1:-rsp_OK.txt}
PORT=${PORT:-8300}

#while true; do { echo -e 'HTTP/1.1 200 OK\r\n'; echo $RSP; } | nc -l $PORT -q 1; done
while true; do { 
  >&2 echo `date +%s` "0.0.0.0:$PORT..."

  echo -e 'HTTP/1.1 200 OK\r\n'; 
  #echo -e 'HTTP/1.1 200 OK\r\n\r\n'; 
  #echo $RSP; 
  cat $RSP_FILE

} | nc -l $PORT -q 0; done
