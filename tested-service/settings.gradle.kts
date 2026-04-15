rootProject.name = "demo"

 includeBuild("../sdk/java") {
     dependencySubstitution {
         substitute(module("ru.itmo.config_streamer:sdk")).using(project(":sdk"))
     }
 }
