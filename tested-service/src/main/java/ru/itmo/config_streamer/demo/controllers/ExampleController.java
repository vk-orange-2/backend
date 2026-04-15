package ru.itmo.config_streamer.demo.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.itmo.config_streamer.sdk.Client;
import ru.itmo.config_streamer.sdk.Config;

@RestController
@RequestMapping("/configs")
public class ExampleController {
    @Autowired
    Client client;

    @GetMapping
    Config getConfigByKey(@RequestParam String key) {
        return client.get(key);
    }
}