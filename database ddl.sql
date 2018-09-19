create schema contracts;

create table if not exists contracts
(
	contractid integer not null
		constraint contracts_pkey
			primary key,
	collateral integer,
	dateissued varchar(40),
	dateexpired varchar(40),
	daystocomplete integer,
	endlocationid integer,
	forcorporation boolean,
	issuercorporationid integer,
	issuerid integer,
	price double precision,
	reward double precision,
	startlocationid integer,
	title varchar(100),
	type varchar(50),
	volume double precision
);

create table if not exists contractitems
(
	contractid integer not null
		constraint contractitems_contracts_contractid_fk
			references contracts
				on delete cascade,
	itemid integer not null,
	typeid integer
		constraint contractitems_invtypes_typeid_fk
			references public.invtypes,
	quantity integer,
	me integer,
	te integer,
	runs integer,
	bptype varchar(20),
	required boolean,
	constraint contractitems_pk
		primary key (contractid, itemid)
);

create table if not exists charactertokens
(
	characterid integer not null
		constraint charactertokens_pkey
			primary key,
	refreshtoken varchar(200) not null,
	accesstoken varchar(200),
	updated bigint
);

create table if not exists appraisalcache
(
	argshash integer not null,
	appraisal text,
	runs integer,
	size integer not null,
	keyhash integer not null
		constraint appraisalcache_pk
			primary key
);

create table if not exists contractetags
(
	url varchar(200) not null
		constraint contractetags_pkey
			primary key,
	etag varchar(200) not null
);

create table if not exists updatelog
(
	time bigint not null
		constraint updatelog_pkey
			primary key,
	contracts integer,
	items integer,
	completed boolean,
	duration bigint,
	log text,
	region integer,
	mutateditems integer
);

create table if not exists mutateditems
(
	itemid bigint not null
		constraint mutateditems_pkey
			primary key,
	typeid integer
		constraint mutateditems_invtypes_typeid_fk
			references public.invtypes,
	basetypeid integer
		constraint mutateditems_invtypes__basetypeid__fk
			references public.invtypes,
	mutatortypeid integer
		constraint mutateditems_invtypes__mutatortypeid__fk
			references public.invtypes,
	contractid integer
		constraint mutateditems_contracts_contractid_fk
			references contracts
				on delete cascade
);

create table if not exists mutatedattributes
(
	itemid bigint not null
		constraint mutatedattributes_mutateditems_itemid_fk
			references mutateditems
				on delete cascade,
	attributeid integer not null
		constraint mutatedattributes_dgmattributetypes_attributeid_fk
			references public.dgmattributetypes,
	basevalue double precision,
	newvalue double precision,
	percentchange double precision,
	typeid integer,
	constraint mutatedattributes_pkey
		primary key (itemid, attributeid)
);

