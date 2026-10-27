CREATE TABLE sticker_favorites (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL CHECK(member_id > 0),
    resource_id BIGINT NOT NULL REFERENCES sticker_resources(id),
    created_at BIGINT NOT NULL,
    UNIQUE(member_id, resource_id)
);
CREATE INDEX sticker_favorites_member_order ON sticker_favorites(member_id, created_at DESC, id DESC);
COMMENT ON TABLE sticker_favorites IS 'Account-owned immutable published sticker references; never C-end uploads';
