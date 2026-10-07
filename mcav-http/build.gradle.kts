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
    // Maven consumers do not inherit transitive version constraints from this library's dependencyManagement.
    api(libs.tomcat.embed.core) {
        // Spring already supplies jakarta.annotation-api; keep its exclusion of Tomcat's duplicate annotation classes.
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
