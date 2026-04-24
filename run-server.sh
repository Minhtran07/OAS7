#!/bin/bash
cd "$(dirname "$0")"
mvn -q compile
mvn -q exec:java -Dexec.mainClass="com.auction.server.MainServer"
