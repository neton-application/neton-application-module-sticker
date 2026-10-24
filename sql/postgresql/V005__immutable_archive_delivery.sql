CREATE TABLE sticker_archive_blobs (
    id BIGSERIAL PRIMARY KEY,
    object_key VARCHAR(256) NOT NULL UNIQUE,
    created_at BIGINT NOT NULL
);

CREATE TABLE sticker_archive_snapshots (
    id BIGSERIAL PRIMARY KEY,
    pack_version_id BIGINT NOT NULL UNIQUE REFERENCES sticker_packs(id),
    manifest_json TEXT NOT NULL,
    descriptor_json TEXT NOT NULL,
    cache_blob_id BIGINT REFERENCES sticker_archive_blobs(id),
    created_at BIGINT NOT NULL
);

CREATE INDEX sticker_archive_snapshot_cache ON sticker_archive_snapshots(cache_blob_id);
CREATE INDEX sticker_archive_blob_age ON sticker_archive_blobs(created_at);

-- Only the disposable cache pointer may change. Snapshot wire bytes are immutable.
CREATE FUNCTION sticker_archive_freeze() RETURNS trigger LANGUAGE plpgsql AS '
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.pack_version_id IS DISTINCT FROM OLD.pack_version_id
       OR NEW.manifest_json IS DISTINCT FROM OLD.manifest_json
       OR NEW.descriptor_json IS DISTINCT FROM OLD.descriptor_json
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION ''sticker archive snapshot is immutable'';
    END IF;
    RETURN NEW;
END;
';
CREATE TRIGGER sticker_archive_snapshot_immutable BEFORE UPDATE ON sticker_archive_snapshots
FOR EACH ROW EXECUTE FUNCTION sticker_archive_freeze();
