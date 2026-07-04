FROM eclipse-temurin:17-jdk-jammy AS backend-build

WORKDIR /src/Backend
COPY Backend/.mvn .mvn
COPY Backend/mvnw Backend/pom.xml ./
RUN ./mvnw -B -DskipTests dependency:go-offline

COPY Backend/src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:17-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-venv python3-pip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY RepoSummary/requirements.txt /tmp/reposummary-requirements.txt
RUN python3 -m venv /opt/reposummary-venv \
    && /opt/reposummary-venv/bin/pip install --upgrade pip \
    && /opt/reposummary-venv/bin/pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch==2.5.1+cpu \
    && /opt/reposummary-venv/bin/pip install --no-cache-dir -r /tmp/reposummary-requirements.txt

COPY --from=backend-build /src/Backend/target/*.jar /app/featx-backend.jar
COPY Backend/tools /app/Backend/tools
COPY RepoSummary /app/RepoSummary

RUN mkdir -p /workspace/repos
COPY datasets/repos /workspace/repos

EXPOSE 8080

CMD ["java", "-jar", "/app/featx-backend.jar"]
