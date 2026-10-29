FROM alpine:3.19
COPY shared/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
COPY pulse/bin/pulse /pulse
EXPOSE 9505
ENTRYPOINT ["/entrypoint.sh"]
CMD ["/pulse"]
