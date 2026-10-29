FROM alpine:3.19
COPY shared/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
COPY marshal/marshal /marshal
EXPOSE 8090
ENTRYPOINT ["/entrypoint.sh"]
CMD ["/marshal"]
