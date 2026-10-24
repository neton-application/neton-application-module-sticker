ALTER TABLE sticker_packs ADD COLUMN default_install INT NOT NULL DEFAULT 0 CHECK(default_install IN (0,1));
ALTER TABLE sticker_installs ADD COLUMN status INT NOT NULL DEFAULT 1 CHECK(status IN (0,1));
COMMENT ON COLUMN sticker_packs.default_install IS 'Published opt-in policy: 1 initializes accounts without a prior decision; 0 is opt-in only';
COMMENT ON COLUMN sticker_installs.status IS '1=active install; 0=persistent explicit uninstall, including across pack versions and account logins';
