CREATE TABLE sticker_authors (
 id BIGSERIAL PRIMARY KEY, name VARCHAR(128) NOT NULL, bio TEXT NOT NULL DEFAULT '',
 status INT NOT NULL DEFAULT 1 CHECK(status IN (0,1))
);
CREATE TABLE sticker_resources (
 id BIGSERIAL PRIMARY KEY, object_key TEXT NOT NULL UNIQUE, digest VARCHAR(64) NOT NULL,
 mime_type VARCHAR(32) NOT NULL, width INT NOT NULL, height INT NOT NULL, size BIGINT NOT NULL,
 author_id BIGINT NOT NULL REFERENCES sticker_authors(id), operator_id BIGINT NOT NULL,
 ever_published INT NOT NULL DEFAULT 0 CHECK(ever_published IN (0,1)),
 ready INT NOT NULL DEFAULT 0 CHECK(ready IN (0,1)), created_at BIGINT NOT NULL
);
CREATE TABLE sticker_packs (
 id BIGSERIAL PRIMARY KEY, pack_id VARCHAR(128) NOT NULL, name_json TEXT NOT NULL,
 author_id BIGINT NOT NULL REFERENCES sticker_authors(id), version INT NOT NULL CHECK(version>0),
 publication_state INT NOT NULL DEFAULT 1 CHECK(publication_state IN (1,2,3)),
 cover_id BIGINT REFERENCES sticker_resources(id), input_digest VARCHAR(64) NOT NULL,
 source VARCHAR(16) NOT NULL, operator_id BIGINT NOT NULL, created_at BIGINT NOT NULL,
 UNIQUE(pack_id,version)
);
CREATE UNIQUE INDEX sticker_one_published ON sticker_packs(pack_id) WHERE publication_state=2;
CREATE TABLE sticker_entries (
 id BIGSERIAL PRIMARY KEY, pack_version_id BIGINT NOT NULL REFERENCES sticker_packs(id) ON DELETE CASCADE,
 resource_id BIGINT NOT NULL REFERENCES sticker_resources(id), key VARCHAR(128) NOT NULL,
 emoji VARCHAR(64) NOT NULL DEFAULT '', position INT NOT NULL,
 UNIQUE(pack_version_id,key), UNIQUE(pack_version_id,position)
);
CREATE TABLE sticker_installs (
 id BIGSERIAL PRIMARY KEY, member_id BIGINT NOT NULL, pack_id VARCHAR(128) NOT NULL,
 position INT NOT NULL, installed_at BIGINT NOT NULL, UNIQUE(member_id,pack_id)
);
CREATE INDEX sticker_resource_cleanup ON sticker_resources(created_at) WHERE ever_published=0;
COMMENT ON COLUMN sticker_packs.publication_state IS '1=draft,2=published,3=offline; published versions immutable';
COMMENT ON TABLE sticker_resources IS 'Immutable image identities; ordinary offline/uninstall never delete published resources';
