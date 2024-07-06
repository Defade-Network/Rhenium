plugins {
    id("java")
}

group = "net.defade"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.logging.log4j:log4j-api:2.21.0")
    implementation("org.apache.logging.log4j:log4j-core:2.21.0")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.21.0")

    implementation("com.electronwill.night-config:toml:3.6.7")

    implementation("redis.clients:jedis:5.1.2")
    implementation("org.mongodb:mongo-java-driver:3.12.14")
}