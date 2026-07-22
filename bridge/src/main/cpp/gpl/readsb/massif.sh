#!/bin/bash
trap "ms_print --x=160 --y=40 .massif.out" EXIT
export MASSIF="--vgdb=yes --tool=massif --massif-out-file=.massif.out"
./valgrind.sh
