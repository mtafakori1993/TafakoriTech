FROM alpine:3.19
COPY shared/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
COPY sentinel/bin/sentinel /sentinel
EXPOSE 8094
ENTRYPOINT ["/entrypoint.sh"]
CMD ["/sentinel"]
