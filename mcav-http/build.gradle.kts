plugins {
    id("mcav.http")
}

dependencies {
    api(libs.bundles.spring.boot.web) {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }
    compileOnlyApi(project(":mcav-common"))
    testImplementation(project(":mcav-common"))
    testImplementation(libs.slf4j.simple)
    // the releases that fix the known vulnerabilities of the Tomcat and Jackson Spring Boot brings (see the catalog)
    constraints {
        api(libs.tomcat.embed.core)
        api(libs.tomcat.embed.el)
        api(libs.tomcat.embed.websocket)
        api(libs.jackson.bom)
    }
}
