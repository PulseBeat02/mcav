dependencies {
    api("uk.co.caprica:vlcj:4.10.1")
    api("org.bytedeco:javacv-platform:1.5.11")
    api("com.google.guava:guava:33.4.8-jre")
    api("com.google.code.gson:gson:2.13.1")
    api("org.seleniumhq.selenium:selenium-java:4.32.0")
    api("io.github.bonigarcia:webdrivermanager:6.1.0")
    api("com.shinyhut:vernacular:1.14")
}

tasks {

    java {
        withSourcesJar()
        withJavadocJar()
    }

    withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }

}