#!/bin/bash

# Run ELS as a remote publisher listener process
#
# Run this before any #ote subscriber process.
#
# Requests new collection and targets files from the subscriber.
# This allows the subscriber to make changes without sending those
# to the publisher separately.
#
# This script may be executed from a file browser.
# All logging is written to the ../output directory.
# Any existing log file is deleted first.

base=`dirname $0`
cd "$base"

name=`basename $0 .sh`

if [ ! -e ../output ]; then
    mkdir ../output
fi

if [ -e ../output/${name}.log ]; then
    rm -f ../output/${name}.log
fi

java -jar ${base}/../ELS.jar -d debug --#ote L --authorize password -p ../meta/publisher.json -S  ../meta/subscriber.json -T ../meta/targets.json -f ../output/${name}.log