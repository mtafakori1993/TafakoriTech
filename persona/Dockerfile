FROM alpine:3.19
COPY shared/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
COPY persona/persona /persona
EXPOSE 8095
ENTRYPOINT ["/entrypoint.sh"]
CMD ["/persona"]
