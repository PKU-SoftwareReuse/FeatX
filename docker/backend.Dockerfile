FROM eclipse-temurin:17-jdk-jammy AS javabackend-build

WORKDIR /src/JavaBackend
COPY JavaBackend/.mvn .mvn
COPY JavaBackend/mvnw JavaBackend/pom.xml ./
RUN ./mvnw -B -DskipTests dependency:go-offline

COPY JavaBackend/src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:17-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends git python3 python3-venv python3-pip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY PyBackend/requirements.txt /tmp/pybackend-requirements.txt
RUN python3 -m venv /opt/reposummary-venv \
    && /opt/reposummary-venv/bin/pip install --upgrade pip \
    && /opt/reposummary-venv/bin/pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch==2.5.1+cpu \
    && /opt/reposummary-venv/bin/pip install --no-cache-dir -r /tmp/pybackend-requirements.txt

COPY --from=javabackend-build /src/JavaBackend/target/*.jar /app/featx-javabackend.jar
COPY JavaBackend/tools /app/JavaBackend/tools
COPY PyBackend /app/PyBackend

RUN mkdir -p /workspace/repos
COPY datasets/repos /workspace/repos

EXPOSE 8080

CMD ["java", "-jar", "/app/featx-javabackend.jar"]
