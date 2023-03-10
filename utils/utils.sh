#! /bin/sh
#
# utils.sh
# Copyright (C) 2018 gaspar_d </var/spool/mail/gaspar_d>
#
# Distributed under terms of the MIT license.
#

interface=eth0

##
## A note about our usage of docker
##
## We should be using docker-compose everywhere, but it is slow.
## We prefer relying directly on docker, thus the container to name translation with container_to_name
##
## Please note that we add -t as it is the default in docker-compose and we rely on tty


log() {
    echo -n '$> '

    echo "`echo $@ | tr '\r' '\n'`"
}

container_to_name() {
    container=$1
    echo "${PWD##*/}_${container}_1"
}

container_to_ip() {
    container=$1
    name=$(container_to_name $container)
    echo $(docker exec $name hostname -I)
}

block_host() {
    container=$1
    name=$(container_to_name $container)
    shift 1
    # https://serverfault.com/a/906499
    docker exec --privileged -t $name bash -c "tc qdisc add dev $interface root handle 1: prio" 2>&1
    for ip in $@; do
        docker exec --privileged -t $name bash -c "tc filter add dev $interface protocol ip parent 1: prio 1 u32 match ip dst $ip flowid 1:1" 2>&1
    done
    docker exec --privileged -t $name bash -c "tc filter add dev $interface protocol all parent 1: prio 2 u32 match ip dst 0.0.0.0/0 flowid 1:2" 2>&1
    docker exec --privileged -t $name bash -c "tc filter add dev $interface protocol all parent 1: prio 2 u32 match ip protocol 1 0xff flowid 1:2" 2>&1
    docker exec --privileged -t $name bash -c "tc qdisc add dev $interface parent 1:1 handle 10: netem loss 100%" 2>&1
    docker exec --privileged -t $name bash -c "tc qdisc add dev $interface parent 1:2 handle 20: sfq" 2>&1
}

add_delay() {
    container=$1
    name=$(container_to_name $container)
    shift 1
    latency=$1
    shift 1
    # https://serverfault.com/a/906499
    docker exec --privileged -t $name bash -c "tc qdisc add dev $interface root handle 1: prio" 2>&1
    for ip in $@; do
        docker exec --privileged -t $name bash -c "tc filter add dev $interface protocol ip parent 1: prio 1 u32 match ip dst $ip flowid 1:1" 2>&1
    done
    docker exec --privileged -t $name bash -c "tc filter add dev $interface protocol all parent 1: prio 2 u32 match ip dst 0.0.0.0/0 flowid 1:2" 2>&1
    docker exec --privileged -t $name bash -c "tc filter add dev $interface protocol all parent 1: prio 2 u32 match ip protocol 1 0xff flowid 1:2" 2>&1
    docker exec --privileged -t $name bash -c "tc qdisc add dev $interface parent 1:1 handle 10: netem delay ${latency}ms" 2>&1
    docker exec --privileged -t $name bash -c "tc qdisc add dev $interface parent 1:2 handle 20: sfq" 2>&1
}

remove_partition() {
    for container in $@; do
        name=$(container_to_name $container)
        docker exec --privileged -t $name bash -c "tc qdisc del dev $interface root" 2>&1 > /dev/null
    done
}

send_message() {
    container=$1
    name=$(container_to_name $container)
    shift 1
    msg=$@

    log "Sending messages to $container - $msg"
    #docker exec -t $name bash -c "echo $msg | kafka-console-producer --broker-list localhost:9092 --topic test --sync --request-required-acks -1 --request-timeout-ms 10000"
    docker exec -t $name bash -c "echo $msg | kafka-console-producer --broker-list $name:9092 --topic test --sync --request-required-acks all --property acks=all --sync --request-timeout-ms 60000"
    echo
}

send_message_to_topic() {
    container=$1
    name=$(container_to_name $container)
    shift 1
    topic=$1
    shift 1
    msg=$@

    log "Sending messages to $container - $msg"
    docker-compose exec $container bash -c "echo $msg | kafka-console-producer --bootstrap-server $container:9092 --topic $topic --sync --request-required-acks all --property acks-all --request-timeout-ms 60000"
    echo
}

read_messages() {
    container=$1
    name=$(container_to_name $container)
    number_of_messages_to_read=${2:-1}
    log "Reading $number_of_messages_to_read messages from $container:"
    docker exec -t $name timeout 15 kafka-console-consumer --bootstrap-server localhost:9092 --topic test --from-beginning --timeout-ms 100000 --max-messages $number_of_messages_to_read

    if [ ! $? -eq 0 ]; then
        log "Read unsuccessful"
    fi
}

read_messages_from_topic() {
    container=$1
    shift 1
    topic=$1
    shift 1
    number_of_messages_to_read=$1
    log "Reading $number_of_messages_to_read messages from $container:"
    docker-compose exec  $container timeout 15 kafka-console-consumer --bootstrap-server localhost:9092 --topic $topic --from-beginning --timeout-ms 100000 --max-messages $number_of_messages_to_read

    if [ ! $? -eq 0 ]; then
        log "Read unsuccessful"
    fi

}

get_state() {
    container=$1
    shift 1
    topic=$1

    name=$(container_to_name $container)
    log "State for partition from $container"
    docker exec -t $name zookeeper-shell localhost:2181 get /brokers/topics/$topic/partitions/0/state | grep '{' | grep '}'
}

zookeeper_mode() {
    for container in $@; do
        name=$(container_to_name $container)
	log `docker exec -t $name bash -c "echo stat" | nc localhost 2181`
        mode=$(docker exec -t $name bash -c "echo stat | nc localhost 2181 | grep Mode")

        if [ $? -eq 0 ]; then
            log "$container $mode"
        else
            log "$container has no mode"
        fi
    done
}

create_topic() {
    container=$1
    name=$(container_to_name $container)
    shift 1
    topic=$1
    shift 1
    repl=$1
    shift 1
    min=$1

    log "Creating topic with min.isr=$min and replication factor $repl"
    log `docker exec -t $name kafka-topics --zookeeper localhost:2181 --create --topic $topic --replica-assignment $(seq $repl | xargs | tr ' ' ':') --config min.insync.replicas=$min`
}

create_topic_mrc() {
    container=$1
    name=$(container_to_name $container)
    shift 1
    topic=$1
    shift 1
    partitions=$1
    shift 1
    replicaplacement=$1

    log "Create topic $topic of $partitions partitions and  placement config $replicaplacement"
    log `docker-compose exec $container bash -c "echo '$replicaplacement' > replica-placement.json"`

    log `docker-compose exec $container kafka-topics --bootstrap-server $container:9092 --create --topic $topic --partitions $partitions --replica-placement replica-placement.json`
}

describe_topic() {
   container=$1
   name=$(container_to_name $container)
   shift 1
   topic=$1
   
   log `docker exec -t $name kafka-topics --describe --topic $topic --bootstrap-server localhost:9092`

}

get_controller() {
   container=$1

   log `docker-compose exec $container zookeeper-shell $container:2181 get /controller`

}


print_hw() {
  container=$1
  shift 1
  topic=$1

  docker-compose exec $container kafka-run-class kafka.tools.GetOffsetShell --broker-list $container:9092 --time -1 --topic $topic

}
