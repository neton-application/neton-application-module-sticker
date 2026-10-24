ALTER TABLE sticker_resources ADD COLUMN pag_profile VARCHAR(32);
ALTER TABLE sticker_resources ADD COLUMN pag_metadata_json TEXT;
ALTER TABLE sticker_resources ADD CONSTRAINT sticker_pag_metadata_pair CHECK (
 (pag_profile IS NULL AND pag_metadata_json IS NULL) OR
 (pag_profile = 'pag-sticker-v1' AND pag_metadata_json IS NOT NULL)
);
CREATE TABLE sticker_pag_links (
 id BIGSERIAL PRIMARY KEY,
 fallback_resource_id BIGINT NOT NULL UNIQUE REFERENCES sticker_resources(id),
 pag_resource_id BIGINT NOT NULL REFERENCES sticker_resources(id),
 CHECK (fallback_resource_id <> pag_resource_id)
);
CREATE INDEX sticker_pag_resource_link ON sticker_pag_links(pag_resource_id);
COMMENT ON TABLE sticker_pag_links IS 'Immutable fallback-to-PAG representation association; never redirects historical sticker IDs';
