package org.example

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class YoutubeApplication

fun main(args: Array<String>) {
    runApplication<YoutubeApplication>(*args)
}
