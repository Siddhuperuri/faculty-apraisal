# The public entry point on Railway. Railway terminates TLS and sends the request here on $PORT; this sends /api/* to the
# backend and everything else to the frontend over Railway's private network, and tells the backend who the client is.
# Build context: deploy/   (railway up deploy --path-as-root, with RAILWAY_DOCKERFILE_PATH=railway/nginx.Dockerfile).
FROM nginx:1.30-alpine
COPY nginx/fams-railway.conf.template /etc/nginx/templates/default.conf.template
# Only these variables are substituted into the template (not nginx's own $variables). NGINX_ENTRYPOINT_LOCAL_RESOLVERS makes
# the image read the nameserver Railway gives this container, which is what resolves *.railway.internal.
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 \
    NGINX_ENVSUBST_FILTER='^(FAMS_|PORT$|NGINX_LOCAL_RESOLVERS$)'
