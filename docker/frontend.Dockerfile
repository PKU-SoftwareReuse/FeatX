FROM node:20-bookworm-slim AS frontend-build

WORKDIR /src/Frontend
COPY Frontend/package*.json ./
RUN npm install

COPY Frontend/ ./
ARG REACT_APP_API_BASE_URL=/api
ENV REACT_APP_API_BASE_URL=$REACT_APP_API_BASE_URL
RUN npm run build

FROM nginx:1.27-alpine

COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=frontend-build /src/Frontend/build /usr/share/nginx/html

EXPOSE 80
