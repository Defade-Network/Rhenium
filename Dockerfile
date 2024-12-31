FROM alpine/java:21-jre

WORKDIR /rhenium
COPY build/libs/Rhenium-1.0-SNAPSHOT-all.jar /rhenium/rhenium.jar

EXPOSE 6000

ENV JAVA_OPTS=""
CMD ["sh", "-c", "java $JAVA_OPTS -jar rhenium.jar"]