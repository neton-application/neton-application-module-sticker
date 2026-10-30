-- sticker: 1.0.0-beta fresh-install schema. Not an upgrade path.
-- Apply through the migration runner; never edit after this baseline is released.
SET search_path = public;
CREATE OR REPLACE FUNCTION public.sticker_archive_freeze()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.pack_version_id IS DISTINCT FROM OLD.pack_version_id
       OR NEW.manifest_json IS DISTINCT FROM OLD.manifest_json
       OR NEW.descriptor_json IS DISTINCT FROM OLD.descriptor_json
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'sticker archive snapshot is immutable';
    END IF;
    RETURN NEW;
END;
$function$

;
--
--


SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: sticker_archive_blobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sticker_archive_blobs (
    id bigint NOT NULL,
    object_key character varying(256) NOT NULL,
    created_at bigint NOT NULL
);


--
-- Name: sticker_archive_blobs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sticker_archive_blobs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sticker_archive_blobs_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sticker_archive_blobs_id_seq OWNED BY public.sticker_archive_blobs.id;


--
-- Name: sticker_archive_snapshots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sticker_archive_snapshots (
    id bigint NOT NULL,
    pack_version_id bigint NOT NULL,
    manifest_json text NOT NULL,
    descriptor_json text NOT NULL,
    cache_blob_id bigint,
    created_at bigint NOT NULL
);


--
-- Name: sticker_archive_snapshots_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sticker_archive_snapshots_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sticker_archive_snapshots_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sticker_archive_snapshots_id_seq OWNED BY public.sticker_archive_snapshots.id;


--
-- Name: sticker_authors; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sticker_authors (
    id bigint NOT NULL,
    name character varying(128) NOT NULL,
    bio text DEFAULT ''::text NOT NULL,
    status integer DEFAULT 1 NOT NULL,
    CONSTRAINT sticker_authors_status_check CHECK ((status = ANY (ARRAY[0, 1])))
);


--
-- Name: sticker_authors_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sticker_authors_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sticker_authors_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sticker_authors_id_seq OWNED BY public.sticker_authors.id;


--
-- Name: sticker_entries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sticker_entries (
    id bigint NOT NULL,
    pack_version_id bigint NOT NULL,
    resource_id bigint NOT NULL,
    key character varying(128) NOT NULL,
    emoji character varying(64) DEFAULT ''::character varying NOT NULL,
    "position" integer NOT NULL
);


--
-- Name: sticker_entries_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sticker_entries_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sticker_entries_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sticker_entries_id_seq OWNED BY public.sticker_entries.id;


--
-- Name: sticker_favorites; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sticker_favorites (
    id bigint NOT NULL,
    member_id bigint NOT NULL,
    resource_id bigint NOT NULL,
    created_at bigint NOT NULL,
    CONSTRAINT sticker_favorites_member_id_check CHECK ((member_id > 0))
);


--
-- Name: TABLE sticker_favorites; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.sticker_favorites IS 'Account-owned immutable published sticker references; never C-end uploads';


--
-- Name: sticker_favorites_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sticker_favorites_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sticker_favorites_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sticker_favorites_id_seq OWNED BY public.sticker_favorites.id;


--
-- Name: sticker_installs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sticker_installs (
    id bigint NOT NULL,
    member_id bigint NOT NULL,
    pack_id character varying(128) NOT NULL,
    "position" integer NOT NULL,
    installed_at bigint NOT NULL,
    status integer DEFAULT 1 NOT NULL,
    CONSTRAINT sticker_installs_status_check CHECK ((status = ANY (ARRAY[0, 1])))
);


--
-- Name: COLUMN sticker_installs.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sticker_installs.status IS '1=active install; 0=persistent explicit uninstall, including across pack versions and account logins';


--
-- Name: sticker_installs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sticker_installs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sticker_installs_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sticker_installs_id_seq OWNED BY public.sticker_installs.id;


--
-- Name: sticker_packs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sticker_packs (
    id bigint NOT NULL,
    pack_id character varying(128) NOT NULL,
    name_json text NOT NULL,
    author_id bigint NOT NULL,
    version integer NOT NULL,
    publication_state integer DEFAULT 1 NOT NULL,
    cover_id bigint,
    input_digest character varying(64) NOT NULL,
    source character varying(16) NOT NULL,
    operator_id bigint NOT NULL,
    created_at bigint NOT NULL,
    default_install integer DEFAULT 0 NOT NULL,
    CONSTRAINT sticker_packs_default_install_check CHECK ((default_install = ANY (ARRAY[0, 1]))),
    CONSTRAINT sticker_packs_publication_state_check CHECK ((publication_state = ANY (ARRAY[1, 2, 3]))),
    CONSTRAINT sticker_packs_version_check CHECK ((version > 0))
);


--
-- Name: COLUMN sticker_packs.publication_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sticker_packs.publication_state IS '1=draft,2=published,3=offline; published versions immutable';


--
-- Name: COLUMN sticker_packs.default_install; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sticker_packs.default_install IS 'Published opt-in policy: 1 initializes accounts without a prior decision; 0 is opt-in only';


--
-- Name: sticker_packs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sticker_packs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sticker_packs_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sticker_packs_id_seq OWNED BY public.sticker_packs.id;


--
-- Name: sticker_pag_links; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sticker_pag_links (
    id bigint NOT NULL,
    fallback_resource_id bigint NOT NULL,
    pag_resource_id bigint NOT NULL,
    CONSTRAINT sticker_pag_links_check CHECK ((fallback_resource_id <> pag_resource_id))
);


--
-- Name: TABLE sticker_pag_links; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.sticker_pag_links IS 'Immutable fallback-to-PAG representation association; never redirects historical sticker IDs';


--
-- Name: sticker_pag_links_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sticker_pag_links_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sticker_pag_links_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sticker_pag_links_id_seq OWNED BY public.sticker_pag_links.id;


--
-- Name: sticker_resources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sticker_resources (
    id bigint NOT NULL,
    object_key text NOT NULL,
    digest character varying(64) NOT NULL,
    mime_type character varying(32) NOT NULL,
    width integer NOT NULL,
    height integer NOT NULL,
    size bigint NOT NULL,
    author_id bigint NOT NULL,
    operator_id bigint NOT NULL,
    ever_published integer DEFAULT 0 NOT NULL,
    ready integer DEFAULT 0 NOT NULL,
    created_at bigint NOT NULL,
    pag_profile character varying(32),
    pag_metadata_json text,
    CONSTRAINT sticker_pag_metadata_pair CHECK ((((pag_profile IS NULL) AND (pag_metadata_json IS NULL)) OR (((pag_profile)::text = 'pag-sticker-v1'::text) AND (pag_metadata_json IS NOT NULL)))),
    CONSTRAINT sticker_resources_ever_published_check CHECK ((ever_published = ANY (ARRAY[0, 1]))),
    CONSTRAINT sticker_resources_ready_check CHECK ((ready = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE sticker_resources; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.sticker_resources IS 'Immutable image identities; ordinary offline/uninstall never delete published resources';


--
-- Name: sticker_resources_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sticker_resources_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sticker_resources_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sticker_resources_id_seq OWNED BY public.sticker_resources.id;


--
-- Name: sticker_archive_blobs id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_archive_blobs ALTER COLUMN id SET DEFAULT nextval('public.sticker_archive_blobs_id_seq'::regclass);


--
-- Name: sticker_archive_snapshots id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_archive_snapshots ALTER COLUMN id SET DEFAULT nextval('public.sticker_archive_snapshots_id_seq'::regclass);


--
-- Name: sticker_authors id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_authors ALTER COLUMN id SET DEFAULT nextval('public.sticker_authors_id_seq'::regclass);


--
-- Name: sticker_entries id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_entries ALTER COLUMN id SET DEFAULT nextval('public.sticker_entries_id_seq'::regclass);


--
-- Name: sticker_favorites id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_favorites ALTER COLUMN id SET DEFAULT nextval('public.sticker_favorites_id_seq'::regclass);


--
-- Name: sticker_installs id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_installs ALTER COLUMN id SET DEFAULT nextval('public.sticker_installs_id_seq'::regclass);


--
-- Name: sticker_packs id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_packs ALTER COLUMN id SET DEFAULT nextval('public.sticker_packs_id_seq'::regclass);


--
-- Name: sticker_pag_links id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_pag_links ALTER COLUMN id SET DEFAULT nextval('public.sticker_pag_links_id_seq'::regclass);


--
-- Name: sticker_resources id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_resources ALTER COLUMN id SET DEFAULT nextval('public.sticker_resources_id_seq'::regclass);


--
-- Name: sticker_archive_blobs sticker_archive_blobs_object_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_archive_blobs
    ADD CONSTRAINT sticker_archive_blobs_object_key_key UNIQUE (object_key);


--
-- Name: sticker_archive_blobs sticker_archive_blobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_archive_blobs
    ADD CONSTRAINT sticker_archive_blobs_pkey PRIMARY KEY (id);


--
-- Name: sticker_archive_snapshots sticker_archive_snapshots_pack_version_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_archive_snapshots
    ADD CONSTRAINT sticker_archive_snapshots_pack_version_id_key UNIQUE (pack_version_id);


--
-- Name: sticker_archive_snapshots sticker_archive_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_archive_snapshots
    ADD CONSTRAINT sticker_archive_snapshots_pkey PRIMARY KEY (id);


--
-- Name: sticker_authors sticker_authors_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_authors
    ADD CONSTRAINT sticker_authors_pkey PRIMARY KEY (id);


--
-- Name: sticker_entries sticker_entries_pack_version_id_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_entries
    ADD CONSTRAINT sticker_entries_pack_version_id_key_key UNIQUE (pack_version_id, key);


--
-- Name: sticker_entries sticker_entries_pack_version_id_position_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_entries
    ADD CONSTRAINT sticker_entries_pack_version_id_position_key UNIQUE (pack_version_id, "position");


--
-- Name: sticker_entries sticker_entries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_entries
    ADD CONSTRAINT sticker_entries_pkey PRIMARY KEY (id);


--
-- Name: sticker_favorites sticker_favorites_member_id_resource_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_favorites
    ADD CONSTRAINT sticker_favorites_member_id_resource_id_key UNIQUE (member_id, resource_id);


--
-- Name: sticker_favorites sticker_favorites_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_favorites
    ADD CONSTRAINT sticker_favorites_pkey PRIMARY KEY (id);


--
-- Name: sticker_installs sticker_installs_member_id_pack_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_installs
    ADD CONSTRAINT sticker_installs_member_id_pack_id_key UNIQUE (member_id, pack_id);


--
-- Name: sticker_installs sticker_installs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_installs
    ADD CONSTRAINT sticker_installs_pkey PRIMARY KEY (id);


--
-- Name: sticker_packs sticker_packs_pack_id_version_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_packs
    ADD CONSTRAINT sticker_packs_pack_id_version_key UNIQUE (pack_id, version);


--
-- Name: sticker_packs sticker_packs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_packs
    ADD CONSTRAINT sticker_packs_pkey PRIMARY KEY (id);


--
-- Name: sticker_pag_links sticker_pag_links_fallback_resource_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_pag_links
    ADD CONSTRAINT sticker_pag_links_fallback_resource_id_key UNIQUE (fallback_resource_id);


--
-- Name: sticker_pag_links sticker_pag_links_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_pag_links
    ADD CONSTRAINT sticker_pag_links_pkey PRIMARY KEY (id);


--
-- Name: sticker_resources sticker_resources_object_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_resources
    ADD CONSTRAINT sticker_resources_object_key_key UNIQUE (object_key);


--
-- Name: sticker_resources sticker_resources_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_resources
    ADD CONSTRAINT sticker_resources_pkey PRIMARY KEY (id);


--
-- Name: sticker_archive_blob_age; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sticker_archive_blob_age ON public.sticker_archive_blobs USING btree (created_at);


--
-- Name: sticker_archive_snapshot_cache; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sticker_archive_snapshot_cache ON public.sticker_archive_snapshots USING btree (cache_blob_id);


--
-- Name: sticker_favorites_member_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sticker_favorites_member_order ON public.sticker_favorites USING btree (member_id, created_at DESC, id DESC);


--
-- Name: sticker_one_published; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX sticker_one_published ON public.sticker_packs USING btree (pack_id) WHERE (publication_state = 2);


--
-- Name: sticker_pag_resource_link; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sticker_pag_resource_link ON public.sticker_pag_links USING btree (pag_resource_id);


--
-- Name: sticker_resource_cleanup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sticker_resource_cleanup ON public.sticker_resources USING btree (created_at) WHERE (ever_published = 0);


--
-- Name: sticker_archive_snapshots sticker_archive_snapshot_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER sticker_archive_snapshot_immutable BEFORE UPDATE ON public.sticker_archive_snapshots FOR EACH ROW EXECUTE FUNCTION public.sticker_archive_freeze();


--
-- Name: sticker_archive_snapshots sticker_archive_snapshots_cache_blob_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_archive_snapshots
    ADD CONSTRAINT sticker_archive_snapshots_cache_blob_id_fkey FOREIGN KEY (cache_blob_id) REFERENCES public.sticker_archive_blobs(id);


--
-- Name: sticker_archive_snapshots sticker_archive_snapshots_pack_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_archive_snapshots
    ADD CONSTRAINT sticker_archive_snapshots_pack_version_id_fkey FOREIGN KEY (pack_version_id) REFERENCES public.sticker_packs(id);


--
-- Name: sticker_entries sticker_entries_pack_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_entries
    ADD CONSTRAINT sticker_entries_pack_version_id_fkey FOREIGN KEY (pack_version_id) REFERENCES public.sticker_packs(id) ON DELETE CASCADE;


--
-- Name: sticker_entries sticker_entries_resource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_entries
    ADD CONSTRAINT sticker_entries_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES public.sticker_resources(id);


--
-- Name: sticker_favorites sticker_favorites_resource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_favorites
    ADD CONSTRAINT sticker_favorites_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES public.sticker_resources(id);


--
-- Name: sticker_packs sticker_packs_author_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_packs
    ADD CONSTRAINT sticker_packs_author_id_fkey FOREIGN KEY (author_id) REFERENCES public.sticker_authors(id);


--
-- Name: sticker_packs sticker_packs_cover_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_packs
    ADD CONSTRAINT sticker_packs_cover_id_fkey FOREIGN KEY (cover_id) REFERENCES public.sticker_resources(id);


--
-- Name: sticker_pag_links sticker_pag_links_fallback_resource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_pag_links
    ADD CONSTRAINT sticker_pag_links_fallback_resource_id_fkey FOREIGN KEY (fallback_resource_id) REFERENCES public.sticker_resources(id);


--
-- Name: sticker_pag_links sticker_pag_links_pag_resource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_pag_links
    ADD CONSTRAINT sticker_pag_links_pag_resource_id_fkey FOREIGN KEY (pag_resource_id) REFERENCES public.sticker_resources(id);


--
-- Name: sticker_resources sticker_resources_author_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sticker_resources
    ADD CONSTRAINT sticker_resources_author_id_fkey FOREIGN KEY (author_id) REFERENCES public.sticker_authors(id);


--
--


SET search_path = public;
