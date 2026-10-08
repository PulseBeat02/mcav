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
    // Maven consumers need direct security pins: Jackson CVE-2026-89425 / CVE-2026-89407; Tomcat GHSA-9xv2-5v5q-p794.
    api(libs.tomcat.embed.core) {
        exclude(group = "org.apache.tomcat", module = "tomcat-annotations-api")
    }
    api(libs.tomcat.embed.el)
    api(libs.tomcat.embed.websocket) {
        exclude(group = "org.apache.tomcat", module = "tomcat-annotations-api")
    }
    api(platform(libs.jackson.bom))
    api(libs.jackson.core)
    api(libs.jackson.databind)
}
