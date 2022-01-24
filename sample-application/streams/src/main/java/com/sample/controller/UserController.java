package com.sample.controller;

import com.sample.streams.TopologyKafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;

@RestController
@RequestMapping("users")
public class UserController {

    @Autowired
    private TopologyKafkaStreams streams;

    private final static DateFormat format = new SimpleDateFormat("dd-MM-yyyy");

    @RequestMapping(value = "/{date}/{id}", method = RequestMethod.GET, produces = "application/json")
    public Float getDayUser(@PathVariable String date, @PathVariable String id) throws ParseException {

        long timeEpoch = format.parse(date).getTime() + 3600000; // add 1 hour

        ReadOnlyWindowStore<String, Float> store = streams
                .getStreams()
                .store(StoreQueryParameters.fromNameAndType(TopologyKafkaStreams.USER_COMMAND_STORE, QueryableStoreTypes.windowStore()));

        return store.fetch(id, timeEpoch);
    }
}
