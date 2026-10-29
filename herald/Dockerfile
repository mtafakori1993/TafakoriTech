FROM alpine:3.19
COPY shared/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
COPY herald/herald /herald
EXPOSE 8092
ENTRYPOINT ["/entrypoint.sh"]
CMD ["/herald"]
