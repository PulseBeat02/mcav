plugins {
    id("mcav.http")
}

val springBootGroup: String = "org.springframework.boot"
val springBootLoggingModule: String = "spring-boot-starter-logging"

val tomcatGroup: String = "org.apache.tomcat"
val tomcatAnnotationsModule: String = "tomcat-annotations-api"

dependencies {
    api(platform(libs.jackson.bom))
    api(libs.bundles.spring.boot.web) {
        exclude(group = springBootGroup, module = springBootLoggingModule)
    }
    api(libs.tomcat.embed.core) {
        exclude(group = tomcatGroup, module = tomcatAnnotationsModule)
    }
    api(libs.tomcat.embed.el)
    api(libs.tomcat.embed.websocket) {
        exclude(group = tomcatGroup, module = tomcatAnnotationsModule)
    }
    api(libs.jackson.core)
    api(libs.jackson.databind)

    compileOnlyApi(project(":mcav-common"))

    testImplementation(project(":mcav-common"))
    testImplementation(libs.slf4j.simple)
}
