--
-- PostgreSQL database dump
--

\restrict t8VpzCSuMl16NzGCwV8XY60cOOgpVMfMhLi8qkV9OLdaxaXUK2El6JcehzF5fB6

-- Dumped from database version 16.11 (Ubuntu 16.11-0ubuntu0.24.04.1)
-- Dumped by pg_dump version 16.11 (Ubuntu 16.11-0ubuntu0.24.04.1)

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

--
-- Name: projectstatusenum; Type: TYPE; Schema: public; Owner: noslop
--

CREATE TYPE public.projectstatusenum AS ENUM (
    'PLANNING',
    'IN_PROGRESS',
    'REVIEW',
    'COMPLETED',
    'PAUSED',
    'STOPPED',
    'CANCELLED'
);


ALTER TYPE public.projectstatusenum OWNER TO noslop;

--
-- Name: projecttypeenum; Type: TYPE; Schema: public; Owner: noslop
--

CREATE TYPE public.projecttypeenum AS ENUM (
    'CINEMATIC_FILM',
    'CORPORATE_VIDEO',
    'ADVERTISEMENT',
    'COMEDY_SKIT',
    'CARTOON',
    'VLOG',
    'PODCAST',
    'MUSIC_VIDEO',
    'DOCUMENTARY',
    'CUSTOM'
);


ALTER TYPE public.projecttypeenum OWNER TO noslop;

--
-- Name: taskstatusenum; Type: TYPE; Schema: public; Owner: noslop
--

CREATE TYPE public.taskstatusenum AS ENUM (
    'PENDING',
    'ASSIGNED',
    'IN_PROGRESS',
    'COMPLETED',
    'FAILED'
);


ALTER TYPE public.taskstatusenum OWNER TO noslop;

--
-- Name: tasktypeenum; Type: TYPE; Schema: public; Owner: noslop
--

CREATE TYPE public.tasktypeenum AS ENUM (
    'SCRIPT_WRITING',
    'PROMPT_ENGINEERING',
    'IMAGE_GENERATION',
    'VIDEO_GENERATION',
    'VIDEO_EDITING',
    'AUDIO_MIXING',
    'COLOR_GRADING',
    'STORYBOARD',
    'RESEARCH',
    'CUSTOM'
);


ALTER TYPE public.tasktypeenum OWNER TO noslop;

--
-- Name: userroleenum; Type: TYPE; Schema: public; Owner: noslop
--

CREATE TYPE public.userroleenum AS ENUM (
    'ADMIN',
    'BASIC'
);


ALTER TYPE public.userroleenum OWNER TO noslop;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: chat_messages; Type: TABLE; Schema: public; Owner: noslop
--

CREATE TABLE public.chat_messages (
    id integer NOT NULL,
    session_id character varying,
    user_id character varying,
    role character varying NOT NULL,
    content text NOT NULL,
    "timestamp" timestamp without time zone,
    meta_data json
);


ALTER TABLE public.chat_messages OWNER TO noslop;

--
-- Name: chat_messages_id_seq; Type: SEQUENCE; Schema: public; Owner: noslop
--

CREATE SEQUENCE public.chat_messages_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.chat_messages_id_seq OWNER TO noslop;

--
-- Name: chat_messages_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: noslop
--

ALTER SEQUENCE public.chat_messages_id_seq OWNED BY public.chat_messages.id;


--
-- Name: chat_sessions; Type: TABLE; Schema: public; Owner: noslop
--

CREATE TABLE public.chat_sessions (
    id character varying NOT NULL,
    user_id character varying,
    title character varying NOT NULL,
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    meta_data json
);


ALTER TABLE public.chat_sessions OWNER TO noslop;

--
-- Name: projects; Type: TABLE; Schema: public; Owner: noslop
--

CREATE TABLE public.projects (
    id character varying NOT NULL,
    title character varying NOT NULL,
    project_type public.projecttypeenum NOT NULL,
    description text NOT NULL,
    status public.projectstatusenum,
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    duration integer,
    style character varying,
    folder_path character varying,
    workflows_count integer,
    media_count integer,
    storage_size_mb double precision,
    reference_media json,
    meta_data json
);


ALTER TABLE public.projects OWNER TO noslop;

--
-- Name: system_config; Type: TABLE; Schema: public; Owner: noslop
--

CREATE TABLE public.system_config (
    key character varying NOT NULL,
    value json NOT NULL,
    updated_at timestamp without time zone
);


ALTER TABLE public.system_config OWNER TO noslop;

--
-- Name: system_settings; Type: TABLE; Schema: public; Owner: noslop
--

CREATE TABLE public.system_settings (
    id integer NOT NULL,
    registration_enabled boolean,
    updated_at timestamp without time zone
);


ALTER TABLE public.system_settings OWNER TO noslop;

--
-- Name: system_settings_id_seq; Type: SEQUENCE; Schema: public; Owner: noslop
--

CREATE SEQUENCE public.system_settings_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.system_settings_id_seq OWNER TO noslop;

--
-- Name: system_settings_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: noslop
--

ALTER SEQUENCE public.system_settings_id_seq OWNED BY public.system_settings.id;


--
-- Name: tasks; Type: TABLE; Schema: public; Owner: noslop
--

CREATE TABLE public.tasks (
    id character varying NOT NULL,
    project_id character varying NOT NULL,
    title character varying NOT NULL,
    description text NOT NULL,
    task_type public.tasktypeenum NOT NULL,
    status public.taskstatusenum,
    assigned_to character varying,
    complexity integer,
    priority integer,
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    started_at timestamp without time zone,
    completed_at timestamp without time zone,
    dependencies json,
    result json,
    meta_data json
);


ALTER TABLE public.tasks OWNER TO noslop;

--
-- Name: users; Type: TABLE; Schema: public; Owner: noslop
--

CREATE TABLE public.users (
    id character varying NOT NULL,
    username character varying NOT NULL,
    email character varying,
    hashed_password character varying NOT NULL,
    role public.userroleenum,
    is_active boolean,
    bio text,
    custom_data json,
    personality_type character varying,
    personality_formality double precision,
    personality_enthusiasm double precision,
    personality_verbosity double precision,
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    last_login timestamp without time zone,
    preferences json
);


ALTER TABLE public.users OWNER TO noslop;

--
-- Name: chat_messages id; Type: DEFAULT; Schema: public; Owner: noslop
--

ALTER TABLE ONLY public.chat_messages ALTER COLUMN id SET DEFAULT nextval('public.chat_messages_id_seq'::regclass);


--
-- Name: system_settings id; Type: DEFAULT; Schema: public; Owner: noslop
--

ALTER TABLE ONLY public.system_settings ALTER COLUMN id SET DEFAULT nextval('public.system_settings_id_seq'::regclass);


--
-- Data for Name: chat_messages; Type: TABLE DATA; Schema: public; Owner: noslop
--

COPY public.chat_messages (id, session_id, user_id, role, content, "timestamp", meta_data) FROM stdin;
\.


--
-- Data for Name: chat_sessions; Type: TABLE DATA; Schema: public; Owner: noslop
--

COPY public.chat_sessions (id, user_id, title, created_at, updated_at, meta_data) FROM stdin;
\.


--
-- Data for Name: projects; Type: TABLE DATA; Schema: public; Owner: noslop
--

COPY public.projects (id, title, project_type, description, status, created_at, updated_at, duration, style, folder_path, workflows_count, media_count, storage_size_mb, reference_media, meta_data) FROM stdin;
\.


--
-- Data for Name: system_config; Type: TABLE DATA; Schema: public; Owner: noslop
--

COPY public.system_config (key, value, updated_at) FROM stdin;
registration_enabled	true	2025-12-20 01:38:30.209328
\.


--
-- Data for Name: system_settings; Type: TABLE DATA; Schema: public; Owner: noslop
--

COPY public.system_settings (id, registration_enabled, updated_at) FROM stdin;
\.


--
-- Data for Name: tasks; Type: TABLE DATA; Schema: public; Owner: noslop
--

COPY public.tasks (id, project_id, title, description, task_type, status, assigned_to, complexity, priority, created_at, updated_at, started_at, completed_at, dependencies, result, meta_data) FROM stdin;
\.


--
-- Data for Name: users; Type: TABLE DATA; Schema: public; Owner: noslop
--

COPY public.users (id, username, email, hashed_password, role, is_active, bio, custom_data, personality_type, personality_formality, personality_enthusiasm, personality_verbosity, created_at, updated_at, last_login, preferences) FROM stdin;
\.


--
-- Name: chat_messages_id_seq; Type: SEQUENCE SET; Schema: public; Owner: noslop
--

SELECT pg_catalog.setval('public.chat_messages_id_seq', 1, false);


--
-- Name: system_settings_id_seq; Type: SEQUENCE SET; Schema: public; Owner: noslop
--

SELECT pg_catalog.setval('public.system_settings_id_seq', 1, false);


--
-- Name: chat_messages chat_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: noslop
--

ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_pkey PRIMARY KEY (id);


--
-- Name: chat_sessions chat_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: noslop
--

ALTER TABLE ONLY public.chat_sessions
    ADD CONSTRAINT chat_sessions_pkey PRIMARY KEY (id);


--
-- Name: projects projects_pkey; Type: CONSTRAINT; Schema: public; Owner: noslop
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_pkey PRIMARY KEY (id);


--
-- Name: system_config system_config_pkey; Type: CONSTRAINT; Schema: public; Owner: noslop
--

ALTER TABLE ONLY public.system_config
    ADD CONSTRAINT system_config_pkey PRIMARY KEY (key);


--
-- Name: system_settings system_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: noslop
--

ALTER TABLE ONLY public.system_settings
    ADD CONSTRAINT system_settings_pkey PRIMARY KEY (id);


--
-- Name: tasks tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: noslop
--

ALTER TABLE ONLY public.tasks
    ADD CONSTRAINT tasks_pkey PRIMARY KEY (id);


--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: noslop
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: noslop
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: users users_username_key; Type: CONSTRAINT; Schema: public; Owner: noslop
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_username_key UNIQUE (username);


--
-- Name: ix_chat_messages_session_id; Type: INDEX; Schema: public; Owner: noslop
--

CREATE INDEX ix_chat_messages_session_id ON public.chat_messages USING btree (session_id);


--
-- Name: chat_messages chat_messages_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: noslop
--

ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: chat_sessions chat_sessions_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: noslop
--

ALTER TABLE ONLY public.chat_sessions
    ADD CONSTRAINT chat_sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: tasks tasks_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: noslop
--

ALTER TABLE ONLY public.tasks
    ADD CONSTRAINT tasks_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- PostgreSQL database dump complete
--

\unrestrict t8VpzCSuMl16NzGCwV8XY60cOOgpVMfMhLi8qkV9OLdaxaXUK2El6JcehzF5fB6

