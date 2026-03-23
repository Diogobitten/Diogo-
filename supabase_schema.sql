-- ============================================================
-- Diogo+ / NuvioTV — Supabase Schema
-- Run this in Supabase Dashboard > SQL Editor
-- ============================================================

-- 1. TABLES
-- ============================================================

-- TV Login Sessions (QR code auth flow)
CREATE TABLE IF NOT EXISTS tv_login_sessions (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    code text NOT NULL UNIQUE,
    device_nonce text NOT NULL,
    device_name text,
    device_user_id uuid REFERENCES auth.users(id),
    status text NOT NULL DEFAULT 'pending', -- pending, approved, exchanged, expired
    approved_user_id uuid REFERENCES auth.users(id),
    redirect_url text,
    expires_at timestamptz NOT NULL,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now()
);

-- Linked Devices (sync code linking)
CREATE TABLE IF NOT EXISTS linked_devices (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.users(id),
    device_user_id uuid NOT NULL REFERENCES auth.users(id),
    device_name text,
    linked_at timestamptz DEFAULT now(),
    UNIQUE(owner_id, device_user_id)
);

-- Sync Codes
CREATE TABLE IF NOT EXISTS sync_codes (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id),
    code text NOT NULL UNIQUE,
    pin text NOT NULL,
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '15 minutes'),
    claimed boolean DEFAULT false,
    created_at timestamptz DEFAULT now()
);

-- Profiles
CREATE TABLE IF NOT EXISTS profiles (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id),
    profile_index int NOT NULL,
    name text NOT NULL DEFAULT '',
    avatar_color_hex text NOT NULL DEFAULT '#1E88E5',
    uses_primary_addons boolean DEFAULT false,
    uses_primary_plugins boolean DEFAULT false,
    avatar_id text,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now(),
    UNIQUE(user_id, profile_index)
);

-- Addons
CREATE TABLE IF NOT EXISTS addons (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id),
    url text NOT NULL,
    name text,
    enabled boolean DEFAULT true,
    sort_order int DEFAULT 0,
    profile_id int DEFAULT 1,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now(),
    UNIQUE(user_id, url, profile_id)
);

-- Plugins
CREATE TABLE IF NOT EXISTS plugins (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id),
    url text NOT NULL,
    name text,
    enabled boolean DEFAULT true,
    sort_order int DEFAULT 0,
    profile_id int DEFAULT 1,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now(),
    UNIQUE(user_id, url, profile_id)
);

-- Library
CREATE TABLE IF NOT EXISTS library (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id),
    content_id text NOT NULL,
    content_type text NOT NULL,
    name text DEFAULT '',
    poster text,
    poster_shape text DEFAULT 'POSTER',
    background text,
    description text,
    release_info text,
    imdb_rating real,
    genres jsonb DEFAULT '[]'::jsonb,
    addon_base_url text,
    added_at bigint DEFAULT 0,
    profile_id int DEFAULT 1,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now(),
    UNIQUE(user_id, content_id, profile_id)
);

-- Watch Progress
CREATE TABLE IF NOT EXISTS watch_progress (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id),
    content_id text NOT NULL,
    content_type text NOT NULL,
    video_id text NOT NULL DEFAULT '',
    season int,
    episode int,
    position bigint NOT NULL DEFAULT 0,
    duration bigint NOT NULL DEFAULT 0,
    last_watched bigint NOT NULL DEFAULT 0,
    progress_key text NOT NULL,
    profile_id int DEFAULT 1,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now(),
    UNIQUE(user_id, progress_key, profile_id)
);

-- Watched Items
CREATE TABLE IF NOT EXISTS watched_items (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id),
    content_id text NOT NULL,
    content_type text NOT NULL,
    title text DEFAULT '',
    season int,
    episode int,
    watched_at bigint NOT NULL DEFAULT 0,
    profile_id int DEFAULT 1,
    created_at timestamptz DEFAULT now(),
    UNIQUE(user_id, content_id, content_type, season, episode, profile_id)
);

-- Avatar Catalog (for custom Supabase-hosted avatars)
CREATE TABLE IF NOT EXISTS avatar_catalog (
    id text PRIMARY KEY,
    display_name text NOT NULL,
    storage_path text NOT NULL,
    category text NOT NULL,
    sort_order int DEFAULT 0,
    bg_color text
);


-- 2. ROW LEVEL SECURITY (RLS)
-- ============================================================

ALTER TABLE tv_login_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE linked_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE sync_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE addons ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugins ENABLE ROW LEVEL SECURITY;
ALTER TABLE library ENABLE ROW LEVEL SECURITY;
ALTER TABLE watch_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE watched_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE avatar_catalog ENABLE ROW LEVEL SECURITY;

-- Users can read/write their own data
CREATE POLICY "Users manage own tv_login_sessions" ON tv_login_sessions FOR ALL USING (device_user_id = auth.uid());
CREATE POLICY "Users manage own linked_devices" ON linked_devices FOR ALL USING (owner_id = auth.uid() OR device_user_id = auth.uid());
CREATE POLICY "Users manage own sync_codes" ON sync_codes FOR ALL USING (user_id = auth.uid());
CREATE POLICY "Users manage own profiles" ON profiles FOR ALL USING (user_id = auth.uid());
CREATE POLICY "Users manage own addons" ON addons FOR ALL USING (user_id = auth.uid());
CREATE POLICY "Users manage own plugins" ON plugins FOR ALL USING (user_id = auth.uid());
CREATE POLICY "Users manage own library" ON library FOR ALL USING (user_id = auth.uid());
CREATE POLICY "Users manage own watch_progress" ON watch_progress FOR ALL USING (user_id = auth.uid());
CREATE POLICY "Users manage own watched_items" ON watched_items FOR ALL USING (user_id = auth.uid());
CREATE POLICY "Anyone can read avatar_catalog" ON avatar_catalog FOR SELECT USING (true);

-- 3. HELPER FUNCTION: get_sync_owner
-- ============================================================

CREATE OR REPLACE FUNCTION get_sync_owner()
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    caller_id uuid := auth.uid();
    owner_id uuid;
BEGIN
    SELECT ld.owner_id INTO owner_id
    FROM linked_devices ld
    WHERE ld.device_user_id = caller_id
    LIMIT 1;

    IF owner_id IS NOT NULL THEN
        RETURN owner_id::text;
    END IF;

    RETURN caller_id::text;
END;
$$;

-- 4. TV LOGIN SESSION RPCs
-- ============================================================

CREATE OR REPLACE FUNCTION start_tv_login_session(
    p_device_nonce text,
    p_redirect_base_url text,
    p_device_name text DEFAULT NULL
)
RETURNS TABLE(code text, web_url text, expires_at text, poll_interval_seconds int)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_code text;
    v_expires timestamptz;
BEGIN
    v_code := upper(substr(md5(random()::text || clock_timestamp()::text), 1, 6));
    v_expires := now() + interval '10 minutes';

    INSERT INTO tv_login_sessions (code, device_nonce, device_name, device_user_id, status, redirect_url, expires_at)
    VALUES (v_code, p_device_nonce, p_device_name, auth.uid(), 'pending',
            p_redirect_base_url || '?code=' || v_code, v_expires);

    RETURN QUERY SELECT
        v_code,
        p_redirect_base_url || '?code=' || v_code,
        to_char(v_expires AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        3;
END;
$$;

CREATE OR REPLACE FUNCTION poll_tv_login_session(
    p_code text,
    p_device_nonce text
)
RETURNS TABLE(status text, expires_at text, poll_interval_seconds int)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_session tv_login_sessions%ROWTYPE;
BEGIN
    SELECT * INTO v_session
    FROM tv_login_sessions
    WHERE tv_login_sessions.code = p_code
      AND tv_login_sessions.device_nonce = p_device_nonce
    LIMIT 1;

    IF v_session IS NULL THEN
        RETURN QUERY SELECT 'not_found'::text, NULL::text, 3;
        RETURN;
    END IF;

    IF v_session.expires_at < now() THEN
        UPDATE tv_login_sessions SET status = 'expired' WHERE id = v_session.id;
        RETURN QUERY SELECT 'expired'::text, NULL::text, 3;
        RETURN;
    END IF;

    RETURN QUERY SELECT
        v_session.status,
        to_char(v_session.expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        3;
END;
$$;

-- Function to approve a TV login session (called from web app)
CREATE OR REPLACE FUNCTION approve_tv_login_session(
    p_code text,
    p_user_id uuid
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    UPDATE tv_login_sessions
    SET status = 'approved',
        approved_user_id = p_user_id,
        updated_at = now()
    WHERE code = p_code
      AND status = 'pending'
      AND expires_at > now();
END;
$$;


-- 5. SYNC RPCs
-- ============================================================

-- Addons
CREATE OR REPLACE FUNCTION sync_push_addons(p_addons jsonb, p_profile_id int DEFAULT 1)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_id uuid := COALESCE((SELECT owner_id FROM linked_devices WHERE device_user_id = auth.uid() LIMIT 1), auth.uid());
BEGIN
    DELETE FROM addons WHERE user_id = v_user_id AND profile_id = p_profile_id;

    INSERT INTO addons (user_id, url, sort_order, profile_id)
    SELECT v_user_id, (item->>'url')::text, (item->>'sort_order')::int, p_profile_id
    FROM jsonb_array_elements(p_addons) AS item;
END;
$$;

-- Plugins
CREATE OR REPLACE FUNCTION sync_push_plugins(p_plugins jsonb, p_profile_id int DEFAULT 1)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_id uuid := COALESCE((SELECT owner_id FROM linked_devices WHERE device_user_id = auth.uid() LIMIT 1), auth.uid());
BEGIN
    DELETE FROM plugins WHERE user_id = v_user_id AND profile_id = p_profile_id;

    INSERT INTO plugins (user_id, url, name, enabled, sort_order, profile_id)
    SELECT v_user_id,
           (item->>'url')::text,
           (item->>'name')::text,
           COALESCE((item->>'enabled')::boolean, true),
           (item->>'sort_order')::int,
           p_profile_id
    FROM jsonb_array_elements(p_plugins) AS item;
END;
$$;

-- Library push
CREATE OR REPLACE FUNCTION sync_push_library(p_items jsonb, p_profile_id int DEFAULT 1)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_id uuid := COALESCE((SELECT owner_id FROM linked_devices WHERE device_user_id = auth.uid() LIMIT 1), auth.uid());
BEGIN
    DELETE FROM library WHERE user_id = v_user_id AND profile_id = p_profile_id;

    INSERT INTO library (user_id, content_id, content_type, name, poster, poster_shape, background, description, release_info, imdb_rating, genres, addon_base_url, profile_id)
    SELECT v_user_id,
           (item->>'content_id')::text,
           (item->>'content_type')::text,
           COALESCE((item->>'name')::text, ''),
           (item->>'poster')::text,
           COALESCE((item->>'poster_shape')::text, 'POSTER'),
           (item->>'background')::text,
           (item->>'description')::text,
           (item->>'release_info')::text,
           (item->>'imdb_rating')::real,
           COALESCE(item->'genres', '[]'::jsonb),
           (item->>'addon_base_url')::text,
           p_profile_id
    FROM jsonb_array_elements(p_items) AS item;
END;
$$;

-- Library pull
CREATE OR REPLACE FUNCTION sync_pull_library(p_profile_id int DEFAULT 1, p_limit int DEFAULT 500, p_offset int DEFAULT 0)
RETURNS TABLE(
    id uuid, user_id uuid, content_id text, content_type text, name text,
    poster text, poster_shape text, background text, description text,
    release_info text, imdb_rating real, genres jsonb, addon_base_url text,
    added_at bigint, profile_id int
)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_id uuid := COALESCE((SELECT owner_id FROM linked_devices WHERE device_user_id = auth.uid() LIMIT 1), auth.uid());
BEGIN
    RETURN QUERY
    SELECT l.id, l.user_id, l.content_id, l.content_type, l.name,
           l.poster, l.poster_shape, l.background, l.description,
           l.release_info, l.imdb_rating, l.genres, l.addon_base_url,
           l.added_at, l.profile_id
    FROM library l
    WHERE l.user_id = v_user_id AND l.profile_id = p_profile_id
    ORDER BY l.created_at DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

-- Watch Progress push
CREATE OR REPLACE FUNCTION sync_push_watch_progress(p_entries jsonb, p_profile_id int DEFAULT 1)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_id uuid := COALESCE((SELECT owner_id FROM linked_devices WHERE device_user_id = auth.uid() LIMIT 1), auth.uid());
BEGIN
    INSERT INTO watch_progress (user_id, content_id, content_type, video_id, season, episode, position, duration, last_watched, progress_key, profile_id)
    SELECT v_user_id,
           (item->>'content_id')::text,
           (item->>'content_type')::text,
           COALESCE((item->>'video_id')::text, ''),
           (item->>'season')::int,
           (item->>'episode')::int,
           (item->>'position')::bigint,
           (item->>'duration')::bigint,
           (item->>'last_watched')::bigint,
           (item->>'progress_key')::text,
           p_profile_id
    FROM jsonb_array_elements(p_entries) AS item
    ON CONFLICT (user_id, progress_key, profile_id)
    DO UPDATE SET
        content_id = EXCLUDED.content_id,
        content_type = EXCLUDED.content_type,
        video_id = EXCLUDED.video_id,
        season = EXCLUDED.season,
        episode = EXCLUDED.episode,
        position = EXCLUDED.position,
        duration = EXCLUDED.duration,
        last_watched = EXCLUDED.last_watched,
        updated_at = now();
END;
$$;

-- Watch Progress pull
CREATE OR REPLACE FUNCTION sync_pull_watch_progress(p_profile_id int DEFAULT 1)
RETURNS TABLE(
    id uuid, user_id uuid, content_id text, content_type text, video_id text,
    season int, episode int, "position" bigint, duration bigint,
    last_watched bigint, progress_key text, profile_id int
)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_id uuid := COALESCE((SELECT owner_id FROM linked_devices WHERE device_user_id = auth.uid() LIMIT 1), auth.uid());
BEGIN
    RETURN QUERY
    SELECT wp.id, wp.user_id, wp.content_id, wp.content_type, wp.video_id,
           wp.season, wp.episode, wp.position, wp.duration,
           wp.last_watched, wp.progress_key, wp.profile_id
    FROM watch_progress wp
    WHERE wp.user_id = v_user_id AND wp.profile_id = p_profile_id
    ORDER BY wp.last_watched DESC;
END;
$$;

-- Watch Progress delete
CREATE OR REPLACE FUNCTION sync_delete_watch_progress(p_keys jsonb, p_profile_id int DEFAULT 1)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_id uuid := COALESCE((SELECT owner_id FROM linked_devices WHERE device_user_id = auth.uid() LIMIT 1), auth.uid());
BEGIN
    DELETE FROM watch_progress
    WHERE user_id = v_user_id
      AND profile_id = p_profile_id
      AND progress_key = ANY(ARRAY(SELECT jsonb_array_elements_text(p_keys)));
END;
$$;

-- Watched Items push
CREATE OR REPLACE FUNCTION sync_push_watched_items(p_items jsonb, p_profile_id int DEFAULT 1)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_id uuid := COALESCE((SELECT owner_id FROM linked_devices WHERE device_user_id = auth.uid() LIMIT 1), auth.uid());
BEGIN
    DELETE FROM watched_items WHERE user_id = v_user_id AND profile_id = p_profile_id;

    INSERT INTO watched_items (user_id, content_id, content_type, title, season, episode, watched_at, profile_id)
    SELECT v_user_id,
           (item->>'content_id')::text,
           (item->>'content_type')::text,
           COALESCE((item->>'title')::text, ''),
           (item->>'season')::int,
           (item->>'episode')::int,
           (item->>'watched_at')::bigint,
           p_profile_id
    FROM jsonb_array_elements(p_items) AS item;
END;
$$;

-- Watched Items pull
CREATE OR REPLACE FUNCTION sync_pull_watched_items(p_profile_id int DEFAULT 1)
RETURNS TABLE(
    id uuid, user_id uuid, content_id text, content_type text,
    title text, season int, episode int, watched_at bigint, profile_id int
)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_id uuid := COALESCE((SELECT owner_id FROM linked_devices WHERE device_user_id = auth.uid() LIMIT 1), auth.uid());
BEGIN
    RETURN QUERY
    SELECT wi.id, wi.user_id, wi.content_id, wi.content_type,
           wi.title, wi.season, wi.episode, wi.watched_at, wi.profile_id
    FROM watched_items wi
    WHERE wi.user_id = v_user_id AND wi.profile_id = p_profile_id
    ORDER BY wi.watched_at DESC;
END;
$$;

-- Profiles push
CREATE OR REPLACE FUNCTION sync_push_profiles(p_profiles jsonb)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_id uuid := COALESCE((SELECT owner_id FROM linked_devices WHERE device_user_id = auth.uid() LIMIT 1), auth.uid());
BEGIN
    INSERT INTO profiles (user_id, profile_index, name, avatar_color_hex, uses_primary_addons, uses_primary_plugins, avatar_id)
    SELECT v_user_id,
           (item->>'profile_index')::int,
           COALESCE((item->>'name')::text, ''),
           COALESCE((item->>'avatar_color_hex')::text, '#1E88E5'),
           COALESCE((item->>'uses_primary_addons')::boolean, false),
           COALESCE((item->>'uses_primary_plugins')::boolean, false),
           (item->>'avatar_id')::text
    FROM jsonb_array_elements(p_profiles) AS item
    ON CONFLICT (user_id, profile_index)
    DO UPDATE SET
        name = EXCLUDED.name,
        avatar_color_hex = EXCLUDED.avatar_color_hex,
        uses_primary_addons = EXCLUDED.uses_primary_addons,
        uses_primary_plugins = EXCLUDED.uses_primary_plugins,
        avatar_id = EXCLUDED.avatar_id,
        updated_at = now();
END;
$$;

-- Profiles pull
CREATE OR REPLACE FUNCTION sync_pull_profiles()
RETURNS TABLE(
    id uuid, user_id uuid, profile_index int, name text,
    avatar_color_hex text, uses_primary_addons boolean,
    uses_primary_plugins boolean, avatar_id text,
    created_at timestamptz, updated_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_id uuid := COALESCE((SELECT owner_id FROM linked_devices WHERE device_user_id = auth.uid() LIMIT 1), auth.uid());
BEGIN
    RETURN QUERY
    SELECT p.id, p.user_id, p.profile_index, p.name,
           p.avatar_color_hex, p.uses_primary_addons,
           p.uses_primary_plugins, p.avatar_id,
           p.created_at, p.updated_at
    FROM profiles p
    WHERE p.user_id = v_user_id
    ORDER BY p.profile_index;
END;
$$;

-- Delete profile data
CREATE OR REPLACE FUNCTION sync_delete_profile_data(p_profile_id int)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_id uuid := COALESCE((SELECT owner_id FROM linked_devices WHERE device_user_id = auth.uid() LIMIT 1), auth.uid());
BEGIN
    DELETE FROM addons WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM plugins WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM library WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM watch_progress WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM watched_items WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM profiles WHERE user_id = v_user_id AND profile_index = p_profile_id;
END;
$$;

-- 6. SYNC CODE RPCs
-- ============================================================

CREATE OR REPLACE FUNCTION generate_sync_code(p_pin text)
RETURNS TABLE(code text)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_code text;
BEGIN
    v_code := upper(substr(md5(random()::text || clock_timestamp()::text), 1, 8));

    DELETE FROM sync_codes WHERE user_id = auth.uid();

    INSERT INTO sync_codes (user_id, code, pin)
    VALUES (auth.uid(), v_code, p_pin);

    RETURN QUERY SELECT v_code;
END;
$$;

CREATE OR REPLACE FUNCTION get_sync_code(p_pin text)
RETURNS TABLE(code text)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    RETURN QUERY
    SELECT sc.code
    FROM sync_codes sc
    WHERE sc.user_id = auth.uid()
      AND sc.pin = p_pin
      AND sc.expires_at > now()
      AND sc.claimed = false
    LIMIT 1;
END;
$$;

CREATE OR REPLACE FUNCTION claim_sync_code(p_code text, p_pin text, p_device_name text DEFAULT NULL)
RETURNS TABLE(result_owner_id text, success boolean, message text)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_owner_id uuid;
BEGIN
    SELECT sc.user_id INTO v_owner_id
    FROM sync_codes sc
    WHERE sc.code = p_code
      AND sc.pin = p_pin
      AND sc.expires_at > now()
      AND sc.claimed = false;

    IF v_owner_id IS NULL THEN
        RETURN QUERY SELECT NULL::text, false, 'Invalid or expired sync code';
        RETURN;
    END IF;

    IF v_owner_id = auth.uid() THEN
        RETURN QUERY SELECT NULL::text, false, 'Cannot link to yourself';
        RETURN;
    END IF;

    INSERT INTO linked_devices (owner_id, device_user_id, device_name)
    VALUES (v_owner_id, auth.uid(), p_device_name)
    ON CONFLICT (owner_id, device_user_id) DO UPDATE SET device_name = EXCLUDED.device_name;

    UPDATE sync_codes SET claimed = true WHERE code = p_code;

    RETURN QUERY SELECT v_owner_id::text, true, 'Device linked successfully';
END;
$$;

CREATE OR REPLACE FUNCTION unlink_device(p_device_user_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    DELETE FROM linked_devices
    WHERE owner_id = auth.uid()
      AND device_user_id = p_device_user_id;
END;
$$;
