-- extract_meta.lua
-- Lifts the container_id captured from the tail TAG into the record as a field,
-- and truncates it to the standard 12-character short form.
function extract_meta(tag, timestamp, record)
    -- Tag format set in fluent-bit.conf: docker.<container_id>
    local cid = tag:match("docker%.([a-f0-9]+)$")
    if cid then
        record["container_id"] = cid:sub(1, 12)
    end
    return 1, timestamp, record
end
