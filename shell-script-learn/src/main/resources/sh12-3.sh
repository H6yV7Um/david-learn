#!/bin/bash
#Program:
#   Use function to repeat information.
#History:
#   2016/06/16 Jackie First release

PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/local/bin:/usr/local/sbin:~/bin
export PATH

function printit(){
    echo -n "Your choice is $1"  #加上 -n 可以不断行继续在同一行显示
}

echo "This program will print your choice!"
case $1 in
    "one")
        printit 1
        ;;
    "two")
        printit 2
        ;;
    "three")
        printit 3
        ;;
    *)
        echo "Usage $0 {one|two|three}"
        ;;
esac

