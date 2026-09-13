# syntax=docker/dockerfile:1
FROM node:26-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
# API modules already include /api; / makes requests same-origin without /api/api.
ENV VITE_API_BASE_URL=/
ARG VITE_AI_MAX_QUESTION_CHARS=4000
ENV VITE_AI_MAX_QUESTION_CHARS=${VITE_AI_MAX_QUESTION_CHARS}
RUN npm run build

FROM nginx:stable-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
